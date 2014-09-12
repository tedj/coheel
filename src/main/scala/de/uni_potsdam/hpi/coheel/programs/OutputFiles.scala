package de.uni_potsdam.hpi.coheel.programs

import de.uni_potsdam.hpi.coheel.FlinkProgramRunner
import org.apache.flink.api.scala.operators.{CsvInputFormat, CsvOutputFormat}

object OutputFiles {
	lazy val currentPath = FlinkProgramRunner.config.getString("output_files_dir")

	lazy val surfaceProbsPath            = s"file://$currentPath/surface-probs.wiki"
	lazy val contextLinkProbsPath        = s"file://$currentPath/context-link-probs.wiki"
	lazy val languageModelProbsPath      = s"file://$currentPath/language-model-probs.wiki"
	lazy val documentWordCountsPath      = s"file://$currentPath/document-word-counts.wiki"
	lazy val redirectPath                = s"file://$currentPath/redirects.wiki"
	lazy val resolvedRedirectsPath       = s"file://$currentPath/resolved-redirects.wiki"
	lazy val textDumpsPath               = s"file://$currentPath/text-dumps.wiki"
	lazy val surfaceDocumentCountsPath   = s"file://$currentPath/surface-document-counts.wiki"
	lazy val entireTextSurfacesPath      = s"file://$currentPath/entire-text-surfaces.wiki"
	lazy val entireTextSurfaceCountsPath = s"file://$currentPath/entire-text-surface-counts.wiki"
	lazy val surfaceLinkProbsPath        = s"file://$currentPath/surface-link-probs.wiki"

	val textFormat            = CsvOutputFormat[(String, String)]("\n", "\t")
	val textInput             = CsvInputFormat[(String, String)]("\n", '\t')

	val surfaceDocumentFormat = CsvOutputFormat[(String, Int)]("\n", "\t")
	val surfaceDocumentInput  = CsvInputFormat[(String, Int)]("\n", '\t')

	val probOutputFormat      = CsvOutputFormat[(String, String, Double)]("\n", "\t")
	val probInputFormat       = CsvInputFormat[(String, String, Double)]("\n", '\t')

	val linkOccurrenceFormat  = CsvOutputFormat[(String, Int, Int)]("\n", "\t")
}
