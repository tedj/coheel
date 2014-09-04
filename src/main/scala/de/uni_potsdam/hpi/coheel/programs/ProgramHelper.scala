package de.uni_potsdam.hpi.coheel.programs

import org.apache.flink.api.scala.{TextFile, DataSet}
import org.slf4s.Logging
import scala.io.Source
import de.uni_potsdam.hpi.coheel.wiki.{Extractor, WikiPage, WikiPageReader}
import java.io.File
import de.uni_potsdam.hpi.coheel.FlinkProgramRunner

/**
 * Helper object for reused parts of Flink programs.
 */
object ProgramHelper extends Logging {

	val dumpFile = new File(FlinkProgramRunner.config.getString("base_path") + FlinkProgramRunner.config.getString("dump_file"))

	lazy val wikipediaFilesPath = s"file://${dumpFile.getAbsolutePath}"

	def getWikiPages: DataSet[WikiPage] = {
		val input = TextFile(wikipediaFilesPath)
		input.map { file =>
			log.info(file)
			val pageSource = Source.fromFile(s"${dumpFile.getAbsoluteFile.getParent}/$file").mkString
			pageSource
		}.flatMap { pageSource =>
			if (pageSource.startsWith("#")) {
				List[WikiPage]().iterator
			} else {
				val wikiPages = WikiPageReader.xmlToWikiPages(pageSource)
				wikiPages.filter { page => page.ns == 0 && page.source.nonEmpty }.map { wikiPage =>
					try {
						val extractor = new Extractor(wikiPage)
						wikiPage.links = extractor.extractLinks()
						wikiPage.plainText = extractor.extractPlainText()
						wikiPage.source = ""
					} catch {
						case e: Throwable =>
							println(s"Error in ${wikiPage.pageTitle}")
					}
					wikiPage
				}
			}
		}
	}
}
