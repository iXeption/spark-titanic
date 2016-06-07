import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.stat.{MultivariateStatisticalSummary, Statistics}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.functions.udf
import org.apache.spark.sql.types.{IntegerType, StructField, StructType}
import org.apache.spark.sql.{DataFrame, Row, SQLContext}

/**
  * Created by Christian on 06.06.2016.
  */
object InOutUtil {

  val normSex = udf((d: String) => d match {
    case null => None
    case s => {
      if (s.equals("male")) Some(0)
      else Some(1)
    }
  })

  val normEmbarked = udf((d: String) => d match {
    case null => None
    case s => {
      if (s.equals("S")) Some(0)
      else if (s.equals("C")) Some(1)
      else Some(2)
    }
  })

  var summary: MultivariateStatisticalSummary = null

  def getTrainingDf(sqlContext: SQLContext, meanMissing: Boolean): DataFrame = {
    val df = sqlContext.read
      .format("com.databricks.spark.csv")
      .option("header", "true") // Use first line of all files as header
      .option("inferSchema", "true") // Automatically infer data types
      .load("src/main/resources/titanic_data/train.csv")

    val projection = df.select(df("Survived"), df("Fare"), normSex(df("Sex")).alias("Sex"),
      df("Age"), df("Pclass"), df("Parch"), df("SibSp"), normEmbarked(df("Embarked")).alias("Embarked"))

    // Get statistics about Fare and Age
    val statsDf = projection.map { row =>
      Vectors.dense(row.getAs[Double]("Fare"), row.getAs[Double]("Age"), row.getAs[Int]("Pclass"), row.getAs[Int]("Sex"))
    }
    summary = Statistics.colStats(statsDf)

    val meanFare = summary.mean(0)
    val meanAge = summary.mean(1)

    // Define udfs, which fill in mean values, if the data has no entry
    val normFare = udf((d: String) => d match {
      case null => Some(meanFare)
      case s => Some(s.toDouble)
    })

    val normAge = udf((d: String) => d match {
      case null => Some(meanAge)
      case s => Some(s.toDouble)
    })

    // select the columns we need and apply the udfs
    if (meanMissing) {
      projection.select(projection("Survived"), normFare(projection("Fare")).alias("Fare"), projection("Sex"),
        normAge(projection("Age")).alias("Age"), projection("Pclass"), projection("Parch"), projection("SibSp"), projection("Embarked"))
    }
    else {
      projection
    }

  }

  def getValidationDf(sqlContext: SQLContext): DataFrame = {
    // load the Test data
    val testDf = sqlContext.read
      .format("com.databricks.spark.csv")
      .option("header", "true") // Use first line of all files as header
      .option("inferSchema", "true") // Automatically infer data types
      .load("src/main/resources/titanic_data/test.csv")

    testDf.select(testDf("PassengerId"), testDf("Fare"), normSex(testDf("Sex")).alias("Sex"),
      testDf("Age"), testDf("Pclass"), testDf("Parch"), testDf("SibSp"), normEmbarked(testDf("Embarked")).alias("Embarked"))
  }

  def saveResult(string: String, sqlContext: SQLContext, rdd: RDD[Row]): Unit = {
    // convert the RDD to Dataframe and save the Data to Disk
    val customSchema = StructType(Array(
      StructField("PassengerId", IntegerType, false),
      StructField("Survived", IntegerType, false))
    )
    val resultDf = sqlContext.createDataFrame(rdd, customSchema)

    resultDf
      //  only one file (one partition)
      .coalesce(1)
      .write
      .format("com.databricks.spark.csv")
      .option("header", "true")
      .save("results/" + string + System.currentTimeMillis())

  }

}