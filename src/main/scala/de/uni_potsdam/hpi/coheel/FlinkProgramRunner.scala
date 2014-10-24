package de.uni_potsdam.hpi.coheel

import java.io.File

import org.apache.commons.lang3.StringUtils
import org.apache.flink.api.common.ProgramDescription
import org.apache.flink.api.scala._
import org.apache.log4j.{Level, Logger}
import com.typesafe.config.{Config, ConfigFactory}
import de.uni_potsdam.hpi.coheel.programs._
import scala.collection.JavaConversions._

/**
 * Basic runner for several Flink programs.
 * Can be configured via several command line arguments.
 * Run without arguments for a list of available options.
 */
// GC parameters: -verbose:gc -XX:+PrintGCTimeStamps -XX:+PrintGCDetails
// Dump downloaded from http://dumps.wikimedia.org/enwiki/latest/
object FlinkProgramRunner {
	val log = Logger.getLogger(getClass)

//	val p1 = new Path("file:/src/test/resources/chunk_dump.wikirun")
//	println(p1.makeQualified(new LocalFileSystem))
//	val p2 = new Path("file:/home/knub/Repositories/coheel-stratosphere/src/test/resources/chunk_dump.wikirun")
//	println(p2)
//	System.exit(3)

	/**
	 * Runnable Flink programs.
	 */
	val programs = Map(
		"main" -> classOf[WikipediaTrainingProgram]
		, "trie" -> classOf[EntireTextSurfacesProgram]
		, "ner-roc" -> classOf[NerRocCurveProgram]
		, "redirects" -> classOf[RedirectResolvingProgram]
	)

	/**
	 * Command line parameter configuration
	 */
	case class Params(dataSetConf: String = "chunk", programName: String = "main", doLogging: Boolean = false)
	val parser = new scopt.OptionParser[Params]("bin/run") {
		head("CohEEL", "0.0.1")
		opt[String]('d', "dataset") required() action { (x, c) =>
			c.copy(dataSetConf = x) } text "specifies the dataset to use, either 'full' or 'chunk'" validate { x =>
			if (List("full", "chunk", "chunk_cluster").contains(x)) success
			else failure("dataset must be either 'full', 'chunk' or 'chunk_cluster'") }
		opt[String]('p', "program") required() action { (x, c) =>
			c.copy(programName = x) } text "specifies the program to run" validate { x =>
			if (programs.keys.contains(x))
				success
			else
				failure("program must be one of the following: " + programs.keys.mkString(",")) }
		opt[Unit]('l', "logging") action { case (_, c) =>
			c.copy(doLogging = true) }
		note("some notes.\n")
		help("help") text "prints this usage text"
	}

	// Configuration for various input and output folders in src/main/resources.
	var config: Config = _

	def main(args: Array[String]): Unit = {
		PerformanceTimer.startTimeFirst("PROGRAM STARTUP")
		// Parse the arguments
		parser.parse(args, Params()) map { params =>
			config = ConfigFactory.load(params.dataSetConf)
			val programName = params.programName
			val program = programs(programName).newInstance()
			runProgram(program)
		} getOrElse {
			parser.showUsage
		}
	}

	def runProgram(program: CoheelProgram with ProgramDescription): Unit = {
		log.info(StringUtils.repeat('#', 140))
		log.info("# " + StringUtils.center(program.getDescription, 136) + " #")
		log.info("# " + StringUtils.rightPad("Dataset: " + config.getString("name"), 136) + " #")
		log.info("# " + StringUtils.rightPad("Base path: " + config.getString("base_path"), 136) + " #")
		log.info("# " + StringUtils.rightPad("Output folder: " + config.getString("output_files_dir"), 136) + " #")
		log.info(StringUtils.repeat('#', 140))

		val processingTime = time {
			val env = if (config.getString("type") == "file")
				ExecutionEnvironment.createLocalEnvironment(1)
			else
				ExecutionEnvironment.createRemoteEnvironment("tenemhead2", 6123, "target/coheel_stratosphere-0.1-SNAPSHOT.jar")
			program.buildProgram(env)

			log.info("Starting ..")
//			Thread.sleep(10000)
//			log.info("NOW!")
			PerformanceTimer.endTimeFirst("PROGRAM STARTUP")

			PerformanceTimer.startTimeFirst("PROGRAM EXECUTION")
			PerformanceTimer.startTimeFirst("FIRST OPERATOR")
			env.execute()
			PerformanceTimer.endTimeFirst("PROGRAM EXECUTION")
//			FileUtils.writeStringToFile(new File("plan.json"), env.getExecutionPlan(), "UTF-8")

			PerformanceTimer.printTimerEvents()
		} * 10.2 * 1024 /* full data dump size*/ / 42.7 /* test dump size */ / 60 /* in minutes */ / 60 /* in hours */
		if (config.getBoolean("print_approximation"))
			log.info(f"Approximately $processingTime%.2f hours on the full dump, one machine.")
	}
	def time[R](block: => R): Double = {
		val start = System.nanoTime()
		val result = block
		val end = System.nanoTime()
		val time = (end - start) / 1000 / 1000 / 1000
		log.info("Took " + time + " s.")
		time
	}
}
