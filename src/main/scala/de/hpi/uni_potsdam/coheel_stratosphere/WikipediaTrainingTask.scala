package de.hpi.uni_potsdam.coheel_stratosphere

import eu.stratosphere.api.scala._
import eu.stratosphere.api.scala.operators._
import eu.stratosphere.api.common.{Program, ProgramDescription, Plan}
import de.hpi.uni_potsdam.coheel_stratosphere.wiki.{TextAnalyzer, WikiPageReader, LinkExtractor}
import scala.xml.XML
import scala.io.Source


class WikipediaTrainingTask extends Program with ProgramDescription {

	lazy val currentPath = System.getProperty("user.dir")

	override def getPlan(args: String*): Plan = {
		val input = TextFile(s"file://$currentPath/src/test/resources/wikipedia_files.txt")
		val pageSource = input.map { file =>
			val pageSource = Source.fromFile(s"src/test/resources/$file").mkString
			pageSource
		}

		val linkCountPlan = buildLinkCountPlan(pageSource)
		val wordCountPlan = buildWordCountPlan(pageSource)
		val plan = new ScalaPlan(Seq(linkCountPlan, wordCountPlan))

		plan
	}

	def buildLinkCountPlan(pageSource: DataSet[String]): ScalaSink[(String, String, Int)] = {
		val links = pageSource.flatMap { pageSource =>
			val extractor = new LinkExtractor()
			val wikiPage = WikiPageReader.xmlToWikiPage(XML.loadString(pageSource))
			val links = extractor.extractLinks(wikiPage)
			links
		} map {
			(_, 1)
		}
		val linkCounts = links.groupBy { case (link, _) => link }
			.reduce { (l1, l2) => (l1._1, l1._2 + l2._2) }
			.map { case (link, count) => (link.text, link.destination, count) }

		val countsOutput = linkCounts.write(s"file://$currentPath/testoutput/link-counts", CsvOutputFormat())
		countsOutput
	}

	def buildWordCountPlan(pageSource: DataSet[String]): ScalaSink[(String, String, Int)] = {
		val wordCount = pageSource.flatMap { pageSource =>
			val (title, text) = WikiPageReader.xmlToPlainText(XML.loadString(pageSource))
			val analyzer = new TextAnalyzer
			val tokens = analyzer.analyze(text).map { token => (title, token) }
			tokens
		} map { case (title, token) =>
			(title, token, 1)
		}
		val languageModel = wordCount.groupBy { case (title, token, _) => (title, token) }
			.reduce { (t1, t2) => (t1._1, t1._2, t1._3 + t2._3) }

		val tokensOutput = languageModel.write(s"file://$currentPath/testoutput/language-models", CsvOutputFormat())
		tokensOutput
	}

	override def getDescription = "Training the model parameters for CohEEL."
}
