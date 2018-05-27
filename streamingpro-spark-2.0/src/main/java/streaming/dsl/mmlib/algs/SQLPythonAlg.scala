package streaming.dsl.mmlib.algs

import java.io.File
import java.nio.file.{Files, Paths}
import java.util
import java.util.UUID

import org.apache.commons.io.{FileUtils, IOUtils}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.TaskContext
import org.apache.spark.api.python.WowPythonRunner
import org.apache.spark.ml.linalg.SQLDataTypes._
import org.apache.spark.ps.cluster.Message
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, Row, SaveMode, SparkSession, functions => F}
import org.apache.spark.util.ObjPickle._
import org.apache.spark.util.VectorSerDer._
import org.apache.spark.util.{ExternalCommandRunner, ObjPickle, VectorSerDer, WowMD5}
import streaming.core.strategy.platform.{PlatformManager, SparkRuntime}
import streaming.dsl.mmlib.SQLAlg
import streaming.dsl.mmlib.algs.SQLPythonFunc._

import scala.collection.JavaConverters._

/**
  * Created by allwefantasy on 5/2/2018.
  */
class SQLPythonAlg extends SQLAlg with Functions {
  override def train(df: DataFrame, path: String, params: Map[String, String]): Unit = {
    val (kafkaParam, newRDD) = writeKafka(df, path, params)
    val systemParam = mapParams("systemParam", params)

    val stopFlagNum = newRDD.getNumPartitions

    val fitParam = arrayParams("fitParam", params).zipWithIndex
    val fitParamRDD = df.sparkSession.sparkContext.parallelize(fitParam, fitParam.length)
    val pythonPath = systemParam.getOrElse("pythonPath", "python")
    val pythonVer = systemParam.getOrElse("pythonVer", "2.7")

    val userPythonScript = loadUserDefinePythonScript(params, df.sparkSession)

    val schema = df.schema
    var rows = Array[Array[Byte]]()
    //目前我们只支持同一个测试集
    if (params.contains("validateTable")) {
      val validateTable = params("validateTable")
      rows = df.sparkSession.table(validateTable).rdd.mapPartitions { iter =>
        ObjPickle.pickle(iter, schema)
      }.collect()
    }
    val rowsBr = df.sparkSession.sparkContext.broadcast(rows)


    val wowRDD = fitParamRDD.map { paramAndIndex =>
      val f = paramAndIndex._1
      val algIndex = paramAndIndex._2
      val paramMap = new util.HashMap[String, Object]()
      var item = f.asJava
      if (!f.contains("modelPath")) {
        item = (f + ("modelPath" -> path)).asJava
      }

      val pythonScript = findPythonScript(userPythonScript, f, "sk")



      val tempModelLocalPath = s"/tmp/${UUID.randomUUID().toString}/${algIndex}"

      paramMap.put("fitParam", item)

      val kafkaP = kafkaParam + ("group_id" -> (kafkaParam("group_id") + "_" + algIndex))
      paramMap.put("kafkaParam", kafkaP.asJava)

      paramMap.put("internalSystemParam", Map(
        "stopFlagNum" -> stopFlagNum,
        "tempModelLocalPath" -> tempModelLocalPath
      ).asJava)
      paramMap.put("systemParam", systemParam.asJava)



      val res = ExternalCommandRunner.run(Seq(pythonPath, pythonScript.fileName),
        paramMap,
        MapType(StringType, MapType(StringType, StringType)),
        pythonScript.fileContent,
        pythonScript.fileName, modelPath = path, validateData = rowsBr.value
      )

      val score = recordUserLog(algIndex, pythonScript, kafkaParam, res)

      //模型保存到hdfs上
      val fs = FileSystem.get(new Configuration())
      fs.delete(new Path(SQLPythonFunc.getAlgModelPath(path)), true)
      fs.copyFromLocalFile(new Path(tempModelLocalPath),
        new Path(SQLPythonFunc.getAlgModelPath(path)))

      // delete local model
      FileUtils.deleteDirectory(new File(tempModelLocalPath))

      Row.fromSeq(Seq(path, algIndex, pythonScript.fileName, score))
    }
    df.sparkSession.createDataFrame(wowRDD, StructType(Seq(
      StructField("modelPath", StringType),
      StructField("algIndex", IntegerType),
      StructField("alg", StringType),
      StructField("score", DoubleType)
    ))).
      write.
      mode(SaveMode.Overwrite).
      parquet(SQLPythonFunc.getAlgMetalPath(path) + "/0")

    val tempRDD = df.sparkSession.sparkContext.parallelize(Seq(Map("pythonPath" -> pythonPath, "pythonVer" -> pythonVer)), 1).map { f =>
      Row.fromSeq(Seq(f))
    }
    df.sparkSession.createDataFrame(tempRDD, StructType(Seq(StructField("systemParam", MapType(StringType, StringType))))).
      write.
      mode(SaveMode.Overwrite).
      parquet(SQLPythonFunc.getAlgMetalPath(path) + "/1")

  }

  override def load(sparkSession: SparkSession, path: String, params: Map[String, String]): Any = {
    val models = sparkSession.read.parquet(SQLPythonFunc.getAlgMetalPath(path) + "/0")
      .collect()
      .map(f => (f(3).asInstanceOf[Double], f(0).asInstanceOf[String]))
      .toSeq.sortBy(f => f._1)(Ordering[Double].reverse).take(1).map(f => f._2)

    val metas = sparkSession.read.parquet(SQLPythonFunc.getAlgMetalPath(path) + "/1").collect().map(f => f.get(0).asInstanceOf[Map[String, String]]).toSeq
    // make sure every executor have the model in local directory.
    // we should unregister manually
    models.foreach { modelPath =>
      if (sparkSession.sparkContext.isLocal) {
        val psDriverBackend = PlatformManager.getRuntime.asInstanceOf[SparkRuntime].localSchedulerBackend
        val tempModelLocalPath = SQLPythonFunc.getLocalTempModelPath(modelPath)
        psDriverBackend.localEndpoint.askSync[Boolean](Message.CopyModelToLocal(modelPath, tempModelLocalPath))
      } else {
        val psDriverBackend = PlatformManager.getRuntime.asInstanceOf[SparkRuntime].psDriverBackend
        val tempModelLocalPath = SQLPythonFunc.getLocalTempModelPath(modelPath)
        psDriverBackend.psDriverRpcEndpointRef.askSync[Boolean](Message.CopyModelToLocal(modelPath, tempModelLocalPath))
      }
    }
    (models, metas)
  }

  override def predict(sparkSession: SparkSession, _model: Any, name: String, params: Map[String, String]): UserDefinedFunction = {
    val (modelsTemp, metasTemp) = _model.asInstanceOf[(Seq[String], Seq[Map[String, String]])]
    val models = sparkSession.sparkContext.broadcast(modelsTemp)

    val pythonPath = metasTemp(0)("pythonPath")
    val pythonVer = metasTemp(0)("pythonVer")

    val userPythonScript = findPythonPredictScript(sparkSession, params, "sk_predict.py")

    val maps = new util.HashMap[String, java.util.Map[String, String]]()
    val item = new util.HashMap[String, String]()
    item.put("funcPath", "/tmp/" + System.currentTimeMillis())
    maps.put("systemParam", item)
    //driver 节点执行
    val res = ExternalCommandRunner.run(Seq(pythonPath, userPythonScript.fileName),
      maps,
      MapType(StringType, MapType(StringType, StringType)),
      userPythonScript.fileContent,
      userPythonScript.fileName, modelPath = null
    )
    res.foreach(f => f)
    val command = Files.readAllBytes(Paths.get(item.get("funcPath")))

    val f = (v: org.apache.spark.ml.linalg.Vector, modelPath: String) => {
      val modelRow = InternalRow.fromSeq(Seq(SQLPythonFunc.getLocalTempModelPath(modelPath)))
      val v_ser = pickleInternalRow(Seq(ser_vector(v)).toIterator, vector_schema())
      val v_ser2 = pickleInternalRow(Seq(modelRow).toIterator, StructType(Seq(StructField("modelPath", StringType))))
      val v_ser3 = v_ser ++ v_ser2
      val iter = WowPythonRunner.run(pythonPath, pythonVer, command, v_ser3, TaskContext.get().partitionId(), Array())
      val a = iter.next()
      VectorSerDer.deser_vector(unpickle(a).asInstanceOf[java.util.ArrayList[Object]].get(0))
    }

    val f2 = (v: org.apache.spark.ml.linalg.Vector) => {
      models.value.map { modelPath =>
        val resV = f(v, modelPath)
        (resV(resV.argmax), resV)
      }.sortBy(f => f._1).reverse.head._2
    }

    UserDefinedFunction(f2, VectorType, Some(Seq(VectorType)))
  }
}
