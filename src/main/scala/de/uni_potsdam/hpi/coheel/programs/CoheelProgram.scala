package de.uni_potsdam.hpi.coheel.programs

import org.apache.flink.api.common.ProgramDescription
import org.apache.flink.api.scala.ExecutionEnvironment

abstract class CoheelProgram() extends ProgramDescription {

	var params: Map[String, String] = _
	def buildProgram(env: ExecutionEnvironment): Unit
}
