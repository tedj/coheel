package de.uni_potsdam.hpi.coheel.ml

import java.io.File

import weka.classifiers.bayes.NaiveBayes
import weka.classifiers.{CostMatrix, Evaluation}
import weka.classifiers.functions.{Logistic, MultilayerPerceptron, SMO, SimpleLogistic}
import weka.classifiers.meta.CostSensitiveClassifier
import weka.classifiers.trees.{J48, RandomForest}
import weka.core.{Attribute, FastVector, Instance, Instances}
import weka.filters.Filter
import weka.filters.unsupervised.attribute.Remove

import scala.collection.mutable.ArrayBuffer
import scala.io.Source
import scala.util.Random


object MachineLearningTestSuite {

	val CLASS_INDEX = 9

	def main(args: Array[String]) = {
		println("Reading.")
		val r = new Random(21011991)
		val instanceGroups = readInstancesInGroups()
		val groupsCount = instanceGroups.size
		val trainingRatio = (groupsCount * 0.7).toInt
		println(s"There are $groupsCount instance groups.")

		val randomOrder = r.shuffle(instanceGroups)

		println("Building separate training and validation set.")
		val fullTrainingInstances = buildInstances("train-full",
			randomOrder.take(trainingRatio).flatten)
		val fullTestInstances     = buildInstances("test-full",
			randomOrder.drop(trainingRatio).flatten)

		println("Use all instances")
		println("=" * 80)
		runWithInstances(fullTrainingInstances, fullTestInstances)

		println("Use only one negative example")
		println("=" * 80)
		val oneSampleTrainingInstances = buildInstances("train-one",
			randomOrder.take(trainingRatio).map { group =>
//				if (!group.exists { inst => inst.value(CLASS_INDEX) == 1.0 }) {
//					group.foreach(println)
//					System.exit(10)
//				}
				val positive = group.find { inst => inst.value(CLASS_INDEX) == 1.0 }.headOption
				val negatives = group.filter { inst => inst.value(CLASS_INDEX) == 0.0 }
				val negative = r.shuffle(negatives).headOption
				positive.toBuffer ++ negative.toBuffer
			}.flatten
		)
		runWithInstances(oneSampleTrainingInstances, fullTestInstances)

		// context size fix
		// missing values

		// surface-link-at-all probability?
		// context < 100 ==>  Missing value
	}

	def buildInstance(split: Array[String]): Instance = {
		val attValues = split.map(_.toDouble).array
		val instance = new Instance(1.0, attValues)
		instance
	}

	def readInstancesInGroups(): ArrayBuffer[ArrayBuffer[Instance]] = {
		val scoresFile = new File("cluster-output/raw-scores.tsv")
		val scoresSource = Source.fromFile(scoresFile)
		val groups = ArrayBuffer[ArrayBuffer[Instance]]()
		var currentGroup = ArrayBuffer[Instance]()
		var lastId: Int = -1
		val lines = scoresSource.getLines()
		lines.drop(1)
		lines.foreach { line =>
			val split = line.split("\t")
			val id = split.head.toInt
			if (id != lastId && currentGroup.nonEmpty) {
				groups += currentGroup.clone()
				currentGroup.clear()
				lastId = id
			}
			currentGroup += buildInstance(split)
		}
		groups
	}

	def runWithInstances(training: Instances, test: Instances): Unit = {
		val remove = new Remove
		remove.setAttributeIndices("1")
		remove.setInputFormat(training)
		Filter.useFilter(training, remove)
		Filter.useFilter(test, remove)

		classifiers.foreach { case (name, classifier) =>
			println(name)
			val evaluation = new Evaluation(training)
			classifier.buildClassifier(training)
			evaluation.evaluateModel(classifier, test)
			System.out.println(f"P: ${evaluation.precision(1)}%.3f, R: ${evaluation.recall(1)}%.3f")
		}
		println("-" * 80)
	}

	val featureDefinition = {
		val attrs = new FastVector(10)
		attrs.addElement(new Attribute("id"))
		attrs.addElement(new Attribute("prom"))
		attrs.addElement(new Attribute("promRank"))
		attrs.addElement(new Attribute("promDeltaTop"))
		attrs.addElement(new Attribute("promDeltaSucc"))
		attrs.addElement(new Attribute("context"))
		attrs.addElement(new Attribute("contextRank"))
		attrs.addElement(new Attribute("contextDeltaTop"))
		attrs.addElement(new Attribute("contextDeltaSucc"))
		val classAttrValues = new FastVector(2)
		classAttrValues.addElement("0.0")
		classAttrValues.addElement("1.0")
		val classAttr = new Attribute("class", classAttrValues)
		attrs.addElement(classAttr)
		attrs
	}

	val classifiers = {
		val simpleLogistic = new SimpleLogistic
		simpleLogistic.setHeuristicStop(0)
		simpleLogistic.setMaxBoostingIterations(1500)
		simpleLogistic.setErrorOnProbabilities(true)

		val nb1 = new NaiveBayes
		nb1.setUseSupervisedDiscretization(true)
		val nb2 = new NaiveBayes
		nb2.setUseSupervisedDiscretization(false)

		val base = List(
			new Logistic,
			new J48,
			new SimpleLogistic,
			simpleLogistic,
			new MultilayerPerceptron,
			nb1,
			nb2,
			new RandomForest,
			new SMO
		)
		base.flatMap { classifier =>
			val costMatrixFN = new CostMatrix(2)
			costMatrixFN.setElement(1, 0, 10)

			val costMatrixFP = new CostMatrix(2)
			costMatrixFP.setElement(0, 1, 10)

			val cost1 = new CostSensitiveClassifier
			cost1.setClassifier(classifier)
			cost1.setMinimizeExpectedCost(true)
			cost1.setCostMatrix(costMatrixFN)
			val cost2 = new CostSensitiveClassifier
			cost2.setClassifier(classifier)
			cost2.setCostMatrix(costMatrixFN)

			val cost3 = new CostSensitiveClassifier
			cost3.setClassifier(classifier)
			cost3.setMinimizeExpectedCost(true)
			cost3.setCostMatrix(costMatrixFP)
			val cost4 = new CostSensitiveClassifier
			cost4.setClassifier(classifier)
			cost4.setCostMatrix(costMatrixFP)

			val baseClassifierName = classifier.getClass.getSimpleName
			List(
				(baseClassifierName, classifier),
				(s"$baseClassifierName with 10 x FN cost, minimize expected cost = true", cost1),
				(s"$baseClassifierName with 10 x FN cost, minimize expected cost = false", cost2),
				(s"$baseClassifierName with 10 x FP cost, minimize expected cost = true", cost3),
				(s"$baseClassifierName with 10 x FP cost, minimize expected cost = false", cost4)
			)
		}
	}

	def buildInstances(name: String, instanceSeq: Seq[Instance]): Instances = {
		val instances = new Instances(name, featureDefinition, 10000)
		instanceSeq.foreach { inst =>
			instances.add(inst)
		}
		instances.setClassIndex(CLASS_INDEX)
		instances
	}
}