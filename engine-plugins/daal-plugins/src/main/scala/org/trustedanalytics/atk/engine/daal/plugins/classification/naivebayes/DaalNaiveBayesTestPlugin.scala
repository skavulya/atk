/**
 *  Copyright (c) 2015 Intel Corporation 
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.trustedanalytics.atk.engine.daal.plugins.classification.naivebayes

import org.trustedanalytics.atk.domain.frame.ClassificationMetricValue
import org.trustedanalytics.atk.engine.frame.SparkFrame
import org.trustedanalytics.atk.engine.frame.plugins.{ ClassificationMetrics, ScoreAndLabel }
import org.trustedanalytics.atk.engine.model.Model
import org.trustedanalytics.atk.engine.model.plugins.ModelPluginImplicits._
import org.trustedanalytics.atk.engine.plugin._

//Implicits needed for JSON conversion
import spray.json._
import org.trustedanalytics.atk.domain.DomainJsonProtocol._
import DaalNaiveBayesModelFormat._
import DaalNaiveBayesArgsFormat._

/* Run the NaiveBayes model on the test frame*/

@PluginDoc(oneLine = "Predict test frame labels and return metrics.",
  extended = """Predict the labels for a test frame and run classification metrics on predicted
and target labels.""",
  returns = """A dictionary with classification metrics.
The data returned is composed of the following keys\:

              |  'accuracy' : double
              |  The proportion of predictions that are correctly identified
              |  'confusion_matrix' : dictionary
              |  A table used to describe the performance of a classification model
              |  'f_measure' : double
              |  The harmonic mean of precision and recall
              |  'precision' : double
              |  The proportion of predicted positive instances that are correctly identified
              |  'recall' : double
              |  The proportion of positive instances that are correctly identified.""")
class DaalNaiveBayesTestPlugin extends SparkCommandPlugin[DaalNaiveBayesTestArgs, ClassificationMetricValue] {
  /**
   * The name of the command.
   *
   * The format of the name determines how the plugin gets "installed" in the client layer
   * e.g Python client via code generation.
   */
  override def name: String = "model:daal_naive_bayes/test"

  override def apiMaturityTag = Some(ApiMaturityTag.Alpha)

  /**
   * Get the predictions for observations in a test frame
   *
   * @param invocation information about the user and the circumstances at the time of the call,
   *                   as well as a function that can be called to produce a SparkContext that
   *                   can be used during this invocation.
   * @param arguments user supplied arguments to running this plugin
   * @return a value of type declared as the Return type.
   */
  override def execute(arguments: DaalNaiveBayesTestArgs)(implicit invocation: Invocation): ClassificationMetricValue = {
    val model: Model = arguments.model
    val frame: SparkFrame = arguments.frame

    // Loading model
    val naiveBayesJsObject = model.dataOption.getOrElse(
      throw new RuntimeException("This model has not been trained yet. Please train before trying to predict")
    )
    val naiveBayesModel = naiveBayesJsObject.convertTo[DaalNaiveBayesModelData]
    if (arguments.observationColumns.isDefined) {
      require(naiveBayesModel.observationColumns.length == arguments.observationColumns.get.length,
        "Number of columns for train and predict should be same")
    }

    //predicting a label for the observation columns
    val naiveBayesColumns = arguments.observationColumns.getOrElse(naiveBayesModel.observationColumns)
    val predictColumn = "predicted_class_" + arguments.labelColumn
    val predictFrame = new DaalNaiveBayesPredictAlgorithm(naiveBayesModel, frame.rdd,
      naiveBayesColumns, predictColumn).predict()

    //predicting and testing
    val scoreAndLabelRdd = predictFrame.toScoreAndLabelRdd(row => {
      val labeledPoint = row.valuesAsLabeledPoint(naiveBayesColumns, arguments.labelColumn)
      val score = row.doubleValue(predictColumn)
      ScoreAndLabel(score, labeledPoint.label)
    })

    //Run classification metrics
    naiveBayesModel.numClasses match {
      case 2 => {
        val posLabel: Double = 1.0d
        ClassificationMetrics.binaryClassificationMetrics(scoreAndLabelRdd, posLabel)
      }
      case _ => ClassificationMetrics.multiclassClassificationMetrics(scoreAndLabelRdd)
    }
  }
}
