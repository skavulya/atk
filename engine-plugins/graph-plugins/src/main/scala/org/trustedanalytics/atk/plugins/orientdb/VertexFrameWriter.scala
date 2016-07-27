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
package org.trustedanalytics.atk.plugins.orientdb

import org.apache.spark.atk.graph.VertexFrameRdd
import org.trustedanalytics.atk.event.EventLogging

/**
 * Exports VertexFrameRdd to OrientDB
 *
 * @param vertexFrameRdd vertices frame to be exported to OrientDB
 * @param dbConfigurations OrientDB configurations
 */

class VertexFrameWriter(vertexFrameRdd: VertexFrameRdd, dbConfigurations: DbConfiguration) extends Serializable with EventLogging {

  /**
   * Method to export vertex frame to OrientDb
   *
   * @param batchSize the number of vertices to be committed
   * @return the number of exported vertices
   */
  def exportVertexFrame(batchSize: Int, append: Boolean): Long = {
    val verticesCountRdd = vertexFrameRdd.mapPartitionVertices(iter => {
      var batchCounter = 0L

      val orientGraph = GraphDbFactory.graphDbConnector(dbConfigurations)
      try {
        while (iter.hasNext) {
          val vertexWrapper = iter.next()
          val vertex = vertexWrapper.toVertex
          val vertexWriter = new VertexWriter(orientGraph)
          if (append) {
            vertexWriter.updateOrCreate(vertex)
          }
          else {
            vertexWriter.create(vertex)
          }
          batchCounter += 1
          if (batchCounter % batchSize == 0 && batchCounter != 0) {
            orientGraph.commit()
          }
        }
      }
      catch {
        case e: Exception => {
          orientGraph.rollback()
          error(s"Unable to add vertices to OrientDB graph", exception = e)
          throw new RuntimeException(s"Unable to add vertices to OrientDB graph: ${e.getMessage}")
        }
      }
      finally {
        orientGraph.shutdown(true, true) // commit and close the graph database
      }
      Array(batchCounter).toIterator
    })
    verticesCountRdd.sum().toLong
  }
}
