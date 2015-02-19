package de.uni_potsdam.hpi.coheel.programs

import java.lang.Iterable
import java.util.Date

import de.uni_potsdam.hpi.coheel.FlinkProgramRunner
import de.uni_potsdam.hpi.coheel.datastructures.NewTrie
import de.uni_potsdam.hpi.coheel.debugging.FreeMemory
import de.uni_potsdam.hpi.coheel.io.OutputFiles
import de.uni_potsdam.hpi.coheel.io.OutputFiles._
import de.uni_potsdam.hpi.coheel.programs.DataClasses.{EntireTextSurfaces, SurfaceAsLinkCount, EntireTextSurfaceCounts}
import org.apache.flink.api.common.functions.{BroadcastVariableInitializer, RichFlatMapFunction}
import org.apache.flink.api.scala._
import org.apache.flink.configuration.Configuration
import org.apache.flink.util.Collector
import org.apache.log4j.Logger
import scala.collection.JavaConverters._

object EntireTextSurfacesProgram {
	val BROADCAST_SURFACES = "surfaces"
}
class EntireTextSurfacesProgram extends CoheelProgram[Int] {

	@transient val log = Logger.getLogger(getClass)
	lazy val fileType = FlinkProgramRunner.config.getString("type")
	val params = if (fileType == "file") List(1) else 1 to 10
	override def getDescription = "Wikipedia Extraction: Entire Text Surfaces"

	override def buildProgram(env: ExecutionEnvironment, param: Int): Unit = {
//		val plainTexts = env.readCsvFile[(String, String)](plainTextsPath, OutputFiles.LINE_DELIMITER, OutputFiles.ROW_DELIMITER).name("Parsed Plain-Texts")
		val plainTexts = env.readTextFile(plainTextsPath).name("Plain-Texts").flatMap { line =>
			val split = line.split('\t')
			if (split.size == 2)
				Some((split(0), split(1)))
			else
				None
		}.name("Parsed Plain-Texts")

		val surfaces = env.readTextFile(surfaceDocumentCountsPath + s"/$param").name("Subset of Surfaces")
			.flatMap(new RichFlatMapFunction[String, String] {
			override def open(params: Configuration): Unit = { }
			override def flatMap(line: String, out: Collector[String]): Unit = {
				val split = line.split('\t')
				if (split.size == 3)
					out.collect(split(0))
			}
		}).name("Parsed Surfaces")

		val entireTextSurfaces = plainTexts
			.flatMap(new FindEntireTextSurfacesFlatMap)
			.withBroadcastSet(surfaces, EntireTextSurfacesProgram.BROADCAST_SURFACES)
			.name("Entire-Text-Surfaces-Along-With-Document")

		val surfaceDocumentCounts = env.readTextFile(surfaceDocumentCountsPath).name("Raw-Surface-Document-Counts")

		val entireTextSurfaceCounts = entireTextSurfaces
			.groupBy { _.surface }
			.reduceGroup { group =>
				val surfaces = group.toList
				EntireTextSurfaceCounts(surfaces.head.surface, surfaces.size)
			}
			.name("Entire-Text-Surface-Counts")

		val surfaceLinkProbs = surfaceDocumentCounts.map { line =>
			val split = line.split('\t')
			// not clear, why lines without a count occur, but they do
			try {
				if (split.size < 3)
					SurfaceAsLinkCount(split(0), 0)
				else {
					val (surface, count) = (split(0), split(2).toInt)
					SurfaceAsLinkCount(surface, count)
				}
			} catch {
				case e: NumberFormatException =>
					println(e)
					println(line)
					SurfaceAsLinkCount(split(0), 0)
			}
		}.name("Surface-Document-Counts").join(entireTextSurfaceCounts)
			.where { _.surface }
			.equalTo { _.surface }
			.map { joinResult => joinResult match {
				case (surfaceAsLinkCount, entireTextSurfaceCount) =>
					(surfaceAsLinkCount.surface, entireTextSurfaceCount.count,
						surfaceAsLinkCount.count.toDouble / entireTextSurfaceCount.count.toDouble)
			}
		}.name("Surface-Link-Probs")

		entireTextSurfaces.writeAsTsv(entireTextSurfacesPath + s"/$param-it")
		surfaceLinkProbs.writeAsTsv(surfaceLinkProbsPath + s"/$param-it")
	}
}
class FindEntireTextSurfacesFlatMap extends RichFlatMapFunction[(String, String), EntireTextSurfaces] {
	var trie: NewTrie = _
	var lastChunk = new Date()
	def log = Logger.getLogger(getClass)

	class TrieBroadcastInitializer extends BroadcastVariableInitializer[String, NewTrie] {

		override def initializeBroadcastVariable(surfaces: Iterable[String]): NewTrie = {
			val trieFromBroadcast = new NewTrie
			surfaces.asScala.foreach { surface =>
				trieFromBroadcast.add(surface)
			}
			trieFromBroadcast
		}
	}

	override def open(params: Configuration): Unit = {
		log.info(s"Building trie with ${FreeMemory.get(true)} MB")
		val d1 = new Date
		trie = getRuntimeContext.getBroadcastVariableWithInitializer(EntireTextSurfacesProgram.BROADCAST_SURFACES, new TrieBroadcastInitializer)
		log.info(s"Finished trie initialization in ${(new Date().getTime - d1.getTime) / 1000} s")
		log.info(s"${FreeMemory.get(true)} MB of memory remaining")
	}
	var i = 0
	val OUTPUT_EVERY = 1000
	override def flatMap(plainText: (String, String), out: Collector[EntireTextSurfaces]): Unit = {
		if (i % OUTPUT_EVERY == 0) {
			val nextChunk = new Date()
			val difference = nextChunk.getTime - lastChunk.getTime
			log.info(f"EntireTextSurfaces update: Finished $i%6s, last $OUTPUT_EVERY took $difference%7s ms, remaining memory: ${FreeMemory.get()}%5s MB")
			lastChunk = nextChunk
		}
		i += 1
		findEntireTextSurfaces(plainText, trie).foreach(out.collect)
	}

	def findEntireTextSurfaces(plainText: (String, String), trie: NewTrie): Iterator[EntireTextSurfaces] = {
		val text = plainText._2
		trie.findAllIn(text).toIterator.map { surface => EntireTextSurfaces(plainText._1, surface)}
	}
}
