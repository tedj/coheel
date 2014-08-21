package de.uni_potsdam.hpi.coheel

import java.io.File

import eu.stratosphere.client.LocalExecutor
import org.apache.log4j.{Level, Logger}
import com.typesafe.config.ConfigFactory
import de.uni_potsdam.hpi.coheel.programs.{RedirectResolvingProgram, SurfaceNotALinkCountProgram, WikipediaTrainingProgram}
import org.apache.commons.io.FileUtils
import eu.stratosphere.api.common.Program

object FlinkProgramRunner {

	val config   = ConfigFactory.load()
	turnOffLogging()
	LocalExecutor.setOverwriteFilesByDefault(true)

	def main(args: Array[String]): Unit = {
		// -Xms3g -Xmx7g
		// -verbose:gc -XX:+PrintGCTimeStamps -XX:+PrintGCDetails
		// 4379 pages in the first chunk dump

		val program = Map(
			"main" -> new WikipediaTrainingProgram,
			"surfaces" -> new SurfaceNotALinkCountProgram,
			"redirects" -> new RedirectResolvingProgram)(config.getString("program"))
		runProgram(program)
	}

	def runProgram(program: Program): Unit = {
		println("Parsing wikipedia. Dataset: " + config.getString("name"))
		val processingTime = time {
			// Dump downloaded from http://dumps.wikimedia.org/enwiki/latest/

			val json = LocalExecutor.optimizerPlanAsJSON(program.getPlan())
			FileUtils.writeStringToFile(new File("plan.json"), json, "UTF-8")

			LocalExecutor.execute(program)
		} * 10.2 * 1024 /* full data dump size*/ / 42.7 /* test dump size */ / 60 /* in minutes */ / 60 /* in hours */
		if (config.getBoolean("print_approximation"))
			println(f"Approximately $processingTime%.2f hours on the full dump, one machine.")
	}
	def time[R](block: => R): Double = {
		val start = System.nanoTime()
		val result = block
		val end = System.nanoTime()
		val time = (end - start) / 1000 / 1000 / 1000
		println("Took " + time + " s.")
		time
	}

	def turnOffLogging(): Unit = {
		List(
			classOf[eu.stratosphere.nephele.taskmanager.TaskManager],
			classOf[eu.stratosphere.nephele.execution.ExecutionStateTransition],
			classOf[eu.stratosphere.nephele.client.JobClient],
			classOf[eu.stratosphere.nephele.jobmanager.JobManager],
			classOf[eu.stratosphere.nephele.jobmanager.scheduler.AbstractScheduler],
			classOf[eu.stratosphere.nephele.instance.local.LocalInstanceManager],
			classOf[eu.stratosphere.nephele.executiongraph.ExecutionGraph],
			classOf[eu.stratosphere.compiler.PactCompiler],
			classOf[eu.stratosphere.runtime.io.network.bufferprovider.GlobalBufferPool],
			classOf[eu.stratosphere.runtime.io.network.netty.NettyConnectionManager],
			classOf[eu.stratosphere.nephele.jobmanager.splitassigner.InputSplitAssigner],
			classOf[eu.stratosphere.nephele.jobmanager.splitassigner.InputSplitManager],
			classOf[eu.stratosphere.nephele.jobmanager.splitassigner.file.FileInputSplitList],
			classOf[eu.stratosphere.nephele.jobmanager.scheduler.AbstractScheduler],
			classOf[eu.stratosphere.pact.runtime.iterative.task.IterationTailPactTask[_, _]],
			classOf[eu.stratosphere.pact.runtime.iterative.task.IterationSynchronizationSinkTask],
			classOf[eu.stratosphere.pact.runtime.iterative.task.IterationIntermediatePactTask[_, _]],
			classOf[eu.stratosphere.pact.runtime.iterative.task.IterationHeadPactTask[_, _, _, _]],
			classOf[eu.stratosphere.pact.runtime.iterative.convergence.WorksetEmptyConvergenceCriterion]
		).foreach {
			Logger.getLogger(_).setLevel(Level.WARN)
		}
	}
}