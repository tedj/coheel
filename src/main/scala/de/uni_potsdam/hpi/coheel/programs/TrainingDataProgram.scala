package de.uni_potsdam.hpi.coheel.programs

import java.lang.Iterable

import de.uni_potsdam.hpi.coheel.io.OutputFiles._
import de.uni_potsdam.hpi.coheel.ml.CoheelClassifier.POS_TAG_GROUPS
import de.uni_potsdam.hpi.coheel.programs.DataClasses._
import de.uni_potsdam.hpi.coheel.util.Util
import de.uni_potsdam.hpi.coheel.wiki.FullInfoWikiPage
import org.apache.flink.api.common.functions.{BroadcastVariableInitializer, RichGroupReduceFunction}
import org.apache.flink.api.scala.{ExecutionEnvironment, _}
import org.apache.flink.configuration.Configuration
import org.apache.flink.core.fs.FileSystem
import org.apache.flink.util.Collector

import scala.collection.JavaConverters._
import scala.collection.mutable


/**
  * Creates the training data from wikipedia.
  *
  * This needs the trie files in two halfs under the
  * in the configuration specified paths.
  * bin/prepare-tries.sh can be used to help with downloading
  * the files from hdfs and create two parts, which can then
  * be uploaded manually to the nodes.
  */
class TrainingDataProgram extends CoheelProgram[TrieSelectionStrategy] with Serializable {

	val SAMPLE_FRACTION = if (runsOffline()) 100 else 5000
//	val SAMPLE_NUMBER = if (runsOffline()) 0 else 632
	val SAMPLE_NUMBER = if (runsOffline()) 0 else 3786

	override def getDescription = "Wikipedia Extraction: Build training data"

	def arguments = if (runsOffline())
			List(new OneTrieEverywhereStrategy("output/surface-link-probs.wiki"))
		else List(
			new OneTrieEverywhereStrategy(params.config.getString("first_trie_half")),
			new OneTrieEverywhereStrategy(params.config.getString("second_trie_half"))
	)

	override def buildProgram(env: ExecutionEnvironment, trieSelector: TrieSelectionStrategy): Unit = {
		val trieFileName = trieSelector.getTrieFile.getName

		val wikiPages = readWikiPagesWithFullInfoUnstemmed { pageTitle =>
			Math.abs(pageTitle.hashCode) % SAMPLE_FRACTION == SAMPLE_NUMBER
		}

		wikiPages
			.map { wikiPage => wikiPage.pageTitle }
			.writeAsText(trainingDataPagesPath + s"-$SAMPLE_NUMBER-$trieFileName.wiki", FileSystem.WriteMode.OVERWRITE)

		val linkDestinationsPerEntity = wikiPages.map { wp =>
			LinkDestinations(wp.pageTitle, wp.links.values.map { l =>
				l.destination
			}.toSet)
		}

		val classifiables = wikiPages
			.flatMap(new LinksAsTrainingDataFlatMap(trieSelector))
			.name("Links and possible links")

		classifiables.map { c =>
			val posTags = c.info.posTags
			(c.id, c.surfaceRepr, c.surfaceLinkProb, c.info.source, c.info.destination, s"PosTags(${posTags.mkString(", ")})", c.context.deep)
		}.writeAsTsv(trainingDataClassifiablesPath +  s"-$SAMPLE_NUMBER-$trieFileName.wiki")

		// Fill classifiables with candidates, surface probs and context probs
		val featuresPerGroup = FeatureHelper.buildFeaturesPerGroup(env, classifiables)

		val trainingData = featuresPerGroup
			.reduceGroup(new TrainingDataGroupReduce(TrainingDataStrategies.REMOVE_CANDIDATE_ONLY))
			.withBroadcastSet(linkDestinationsPerEntity, TrainingDataGroupReduce.BROADCAST_LINK_DESTINATIONS_PER_ENTITY)
			.name("Training Data")

		trainingData.writeAsText(trainingDataPath + s"-$SAMPLE_NUMBER-$trieFileName.wiki", FileSystem.WriteMode.OVERWRITE)
	}
}

class LinkDestinationsInitializer extends BroadcastVariableInitializer[LinkDestinations, mutable.Map[String, Set[String]]] {

	override def initializeBroadcastVariable(destinations: Iterable[LinkDestinations]): mutable.Map[String, Set[String]] = {
		val destinationsMap = mutable.Map[String, Set[String]]()
		destinations.asScala.foreach { dest =>
			destinationsMap += dest.entity -> dest.destinations
		}
		destinationsMap
	}
}

object TrainingDataGroupReduce {
	val BROADCAST_LINK_DESTINATIONS_PER_ENTITY = "linkDestinationsPerEntity"
}


/**
  * This creates the training data from the given grouped classifiables by applying
  * the second order functions.
  *
  * It also decides, which classifiables shoud be output at all.
  */


class TrainingDataGroupReduce(trainingDataStrategy: TrainingDataStrategy) extends RichGroupReduceFunction[Classifiable[TrainInfo], String] {
	import CoheelLogger._
	var linkDestinationsPerEntity: mutable.Map[String, Set[String]] = _
	override def open(params: Configuration): Unit = {
		linkDestinationsPerEntity = getRuntimeContext.getBroadcastVariableWithInitializer(
			TrainingDataGroupReduce.BROADCAST_LINK_DESTINATIONS_PER_ENTITY,
			new LinkDestinationsInitializer)
	}

	/**
	  * @param candidatesIt All link candidates with scores (all Classifiable's have the same id).
	  */
	override def reduce(candidatesIt: Iterable[Classifiable[TrainInfo]], out: Collector[String]): Unit = {
		val allCandidates = candidatesIt.asScala.toSeq

		// get all the link destinations from the source entitity of this classifiable
		// remember, all classifiables come from the same link/trie hit, hence it is ok to
		// only access the head
		val linkDestinations = if (allCandidates.head.isTrieHit)
				linkDestinationsPerEntity(allCandidates.head.info.source)
			else
				Set[String]()

		// This variable is necessary for the REMOVE_ENTIRE_GROUP training strategy
		// It tracks, whether at least one candidate is linked from the current page
		var containsCandidateFromLinks = false
		val featureLines = new mutable.ArrayBuffer[FeatureLine[TrainInfo]](allCandidates.size)
		FeatureHelper.applyCoheelFunctions(allCandidates) { featureLine =>
			featureLines += featureLine
			if (linkDestinations.nonEmpty && !containsCandidateFromLinks) {
				containsCandidateFromLinks = linkDestinations.contains(featureLine.candidateEntity)
			}
		}
		if ((trainingDataStrategy == TrainingDataStrategies.REMOVE_CANDIDATE_ONLY) ||
			(trainingDataStrategy == TrainingDataStrategies.REMOVE_ENTIRE_GROUP && !containsCandidateFromLinks)) {
			featureLines.foreach { featureLine =>
				import featureLine._
				def stringInfo = List(id, surfaceRepr, candidateEntity) ::: featureLine.info.modelInfo
				val output = s"${stringInfo.mkString("\t")}\t${featureLine.features.mkString("\t")}"

				// Filter out feature lines with a candidate entity, which is also a link in the source.
				// Taking care, that not all links are filtered out (not the original), i.e. only do this for trie hits
				if (id.startsWith(s"${FeatureHelper.TRIE_HIT_MARKER}-")) {
					// This classifiable/feature line came from a trie hit, we might want to remove it from the
					// training data set:
					// Remove the trie hit, if the candidate entity is linked from the current article.
					// Reasoning: Say, an article contains Angela Merkel as a link. Later, it is referred to as
					// the "merkel" with no link. It would be wrong to learn, that this should not be linked, because
					// it is probably only not linked, because it was already linked in the article.
					if (!linkDestinations.contains(featureLine.candidateEntity))
						out.collect(output)
					else {
						log.info(s"Do not output surface `${featureLine.surfaceRepr}` with candidate '${featureLine.candidateEntity}' from ${featureLine.info.modelInfo}")
					}
				} else {
					// we have a link, just output it
					out.collect(output)
				}
			}
		}
	}
}

class LinksAsTrainingDataFlatMap(trieSelector: TrieSelectionStrategy) extends ReadTrieFromDiskFlatMap[FullInfoWikiPage, Classifiable[TrainInfo]](trieSelector) {
	var nrLinks = 0
	var nrLinksFiltered = 0
	var outputtedTrieHits = 0
	var outputtedLinks = 0
	import CoheelLogger._

	override def flatMap(wikiPage: FullInfoWikiPage, out: Collector[Classifiable[TrainInfo]]): Unit = {
		assert(wikiPage.tags.size == wikiPage.plainText.size)

		val linksWithPositions = wikiPage.links

		val trieHits = trie.findAllInWithTrieHit(wikiPage.plainText).toList
		trieHits/*.groupBy { th =>
			th.startIndex
		}.map { ths =>
			ths._2.maxBy { th => th.length }
		}*/.foreach { trieHit =>
			if (!linksWithPositions.contains(trieHit.startIndex)) {
				val contextOption = Util.extractContext(wikiPage.plainText, trieHit.startIndex)

				contextOption.foreach { context =>
					val tags = wikiPage.tags.slice(trieHit.startIndex, trieHit.startIndex + trieHit.length).toArray
					outputtedTrieHits += 1
					out.collect(Classifiable[TrainInfo](
						// TH for trie hit
						s"${FeatureHelper.TRIE_HIT_MARKER}-${Util.id(wikiPage.pageTitle)}-${trieHit.startIndex}-${trieHit.length}",
						trieHit.s,
						context.toArray,
						surfaceLinkProb = trieHit.prob,
						info = TrainInfo(wikiPage.pageTitle, destination = "", POS_TAG_GROUPS.map { group => if (group.exists(tags.contains(_))) 1.0 else 0.0 })))
				}
			} else {
				log.info(s"Ignoring trie hit $trieHit because it stems from link ${linksWithPositions(trieHit.startIndex)}")
			}
		}

		linksWithPositions.foreach { case (index, link) =>
			// In theory, the index of the link should be in the set of indices proposed by the trie:
			//    assert(hitPoints.contains(index))
			// After all, if this link was found in the first phase, its surface should be in the trie now.
			// The problem, however, is the different tokenization: When tokenizing link text, we only tokenize
			// the small text of the link, while plain text tokenization works on the entire text
			// This tokenization is sometimes different, see the following example:
			//    println(TokenizerHelper.tokenize("Robert V.").mkString(" "))            --> robert v.
			//    println(TokenizerHelper.tokenize("Robert V. The Legacy").mkString(" ")) --> robert v the legaci (dot missing)
			//
			// This could be solved by taking the link tokenization directly from the plain text, however, this would
			// require quite a bit of rewriting.

			val contextOption = Util.extractContext(wikiPage.plainText, index)

			val containsResult = trie.contains(link.surfaceRepr)
			nrLinks += 1
			if (containsResult.asEntry) {
				contextOption.foreach { context =>
					outputtedLinks += 1
					out.collect(
						Classifiable[TrainInfo](
							link.id,
							link.surfaceRepr,
							context.toArray,
							surfaceLinkProb = containsResult.prob,
							info = TrainInfo(link.source, link.destination, POS_TAG_GROUPS.map { group => if (group.exists(link.posTags.contains(_))) 1.0 else 0.0 })))
				}
			} else {
				nrLinksFiltered += 1
			}
		}
	}

	override def close(): Unit = {
		log.info(s"LinksAsTrainingDataFlatMap summary: # Links/(# Links + # TrieHits) = ${outputtedLinks.toDouble * 100 / (outputtedLinks + outputtedTrieHits)} %")
		log.info(s"LinksAsTrainingDataFlatMap summary: # Links filtered/# Links       = $nrLinksFiltered/$nrLinks = ${nrLinksFiltered.toDouble * 100 / nrLinks} %")
	}
}

