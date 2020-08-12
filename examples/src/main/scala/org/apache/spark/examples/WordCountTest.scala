/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// scalastyle:off println
package org.apache.spark.examples

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{Row, SparkSession}


object WordCountTest {

  def main(args: Array[String]) {
    val spark = SparkSession
      .builder
      .appName("WordCountTest")
      .master("local[*]")
      .getOrCreate()

    var rdd1 = spark.sparkContext.makeRDD(Array(("A", 1), ("A", 2), ("B", 1), ("B", 2), ("C", 1)))

    rdd1.combineByKey(
      (v: Int) => v + "_",
      (c: String, v: Int) => c + "@" + v,
      (c1: String, c2: String) => c1 + "$" + c2
    ).collect
    spark.stop()
  }
}

// scalastyle:on println
