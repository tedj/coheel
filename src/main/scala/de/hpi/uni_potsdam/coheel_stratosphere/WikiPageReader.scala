package de.hpi.uni_potsdam.coheel_stratosphere

import scala.xml.Elem
import org.dbpedia.extraction.sources.WikiPage
import org.dbpedia.extraction.wikiparser.WikiTitle
import org.dbpedia.extraction.util.Language

object WikiPageReader {

	def xmlToWikiPage(xml: Elem): WikiPage = {
		for (page <- xml \ "query" \ "pages" \ "page";
		     rev <- page \ "revisions" \ "rev") {
			val contributorID = rev \ "@userid"
			val contributorName = rev \ "@user"
			val format = rev \ "@contentformat"

			return new WikiPage(
				title           = WikiTitle.parse((page \ "@title").head.text, Language.English),
				redirect        = null,
				id              = (page \ "@pageid").head.text,
				revision        = (rev \ "@revid").head.text,
				timestamp       = (rev \ "@timestamp").head.text,
				contributorID   = if (contributorID == null || contributorID.length != 1)
					"0" else contributorID.head.text,
				contributorName = if (contributorName == null || contributorName.length != 1)
					"" else contributorName.head.text,
				source          = rev.text,
				format          = if (format == null || format.length != 1)
					"" else format.head.text
			)
		}
		throw new RuntimeException("No wikipage found!")
	}
}
