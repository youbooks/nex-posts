package spark;

import org.apache.spark.sql.SparkSession;

/**
 * Created by wangwang on 2018/8/17.
 */
public class SparkSql {
    public static void main(String[] args) {
        SparkSession sparkSession = SparkSession
                .builder()
                .appName("Spark Hive Example")
                .enableHiveSupport()
                .getOrCreate();

        sparkSession.sql("select count(amount) from flume where `createTime` > 1534208096000").show();
        System.out.println("");
    }
}