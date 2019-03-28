### Running HDFS

```
第一步:
ssh-keygen -t rsa
cat ~/.ssh/id_rsa.pub >> ~/.ssh/authorized_keys
ssh lcoalhost就能够免密登录了

第二步:
修改hadoop-env.sh:
将
export HADOOP_OPTS="$HADOOP_OPTS -Djava.net.preferIPv4Stack=true"
改为

export HADOOP_OPTS="$HADOOP_OPTS -Djava.net.preferIPv4Stack=true -Djava.security.krb5.realm= -Djava.security.krb5.kdc="
export JAVA_HOME="/Library/Java/JavaVirtualMachines/jdk1.8.0_121.jdk/Contents/Home"
(java_home请写上你本机上jdk安装的位置)

第三步:
修改core-site.xml:
将<configuration></configuration>替换为

<configuration>
  <property>
     <name>hadoop.tmp.dir</name>
<value>/usr/local/Cellar/hadoop/hdfs/tmp</value>
    <description>A base for other temporary directories.</description>
  </property>
  <property>
     <name>fs.default.name</name>
     <value>hdfs://localhost:8020</value>
  </property>
</configuration>

第四步:
修改mapred-site.xml:
<configuration></configuration>替换为

<configuration>
      <property>
        <name>mapred.job.tracker</name>
        <value>localhost:8021</value>
      </property>
</configuration>

第五步:
打开hdfs-site.xml加上

<configuration>
   <property>
     <name>dfs.replication</name>
     <value>1</value>
    </property>
</configuration>

第六步:
hdfs namenode -format

第七步:
配置Hadoop环境变量
打开~/.bash_profile添加:

export HADOOP_HOME=/usr/local/Cellar/hadoop/2.8.0
export PATH=$PATH:$HADOOP_HOME/sbin:$HADOOP_HOME/bin

source ~/.bash_profile

第八步:
启动/关闭HDSF服务

./start-dfs.sh          
./stop-dfs.sh

UI:
http://localhost:50070
```

### Running Hive

```xml
第一步:
配置hive环境变量
export HIVE_HOME=/usr/local/hive
export PATH=$JAVA_HOME/bin:$JRE_HOME/bin:$HADOOP_HOME/bin:$HADOOP_HOME/sbin:$HIVE_HOME/bin:$PATH  

source ~/.bash_profile

第二步:

      $ $HADOOP_HOME/bin/hadoop fs -mkdir       /tmp
      $ $HADOOP_HOME/bin/hadoop fs -mkdir       /user/hive/warehouse
      $ $HADOOP_HOME/bin/hadoop fs -chmod g+w   /tmp
      $ $HADOOP_HOME/bin/hadoop fs -chmod g+w   /user/hive/warehouse

第三步:
vim hive-env.sh：
 
export HADOOP_HOME=/usr/local/hadoop
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64
export HIVE_HOME=/usr/local/hive
export HIVE_CONF_DIR=/usr/local/hive/conf
export HIVE_AUX_JARS_PATH=/usr/local/hive/lib


第四步:
在本地创建相应的文件：
cd /tmp
mkdir hive 
mkdir hive/operation_logs 
mkdir hive/resources

vim hive-site.xml：
 
${system:Java.io.tmpdir}/${hive.session.id}_resources替换为本机路径/Users/wangwang/Downloads/hive/tmp/resources 
${system:java.io.tmpdir}/${system:user.name}/operation_logs替换为本机路径/Users/wangwang/Downloads/hive/tmp/operation_logs
${system:java.io.tmpdir}/${system:user.name}替换为本机路径 /Users/wangwang/Downloads/hive/tmp


第五步:
schematool -dbType derby -initSchema  

hive



使用MySQL做metastore:
第一步:
sudo cp /Users/wangwang/.m2/repository/mysql/mysql-connector-java/5.1.39/mysql-connector-java-5.1.39.jar /Users/wangwang/Downloads/hive/lib/

第二步:
vim hive-site.xml只修改下面的内容，其余不用修改
<configuration>
<property>
	<name>hive.metastore.warehouse.dir</name>
	<value>/user/hive/warehouse</value>
	<description>location of default database for the warehouse</description>
</property>
<property>
	<name>hive.metastore.local</name>hive_db
	<value>true</value>
	<description>Use false if a production metastore server is used</description>
</property>
<property>
	<name>hive.exec.scratchdir</name>
	<value>/tmp/hive</value>
	<description>HDFS root scratch dir for Hive jobs which gets created with write all (733) permission. For each connecting user, an HDFS scratch dir: ${hive.exec.scratchdir}/<username> is created, with ${hive.scratch.dir.permission}.</description>
</property>
<property>
	<name>javax.jdo.option.ConnectionURL</name>
	<value>jdbc:mysql://localhost:3306/hive?createDatabaseIfNoExist=true&amp;useSSL=true</value>
	<description> Roy
  JDBC connect string for a JDBC metastore.
  To use SSL to encrypt/authenticate the connection, provide database-specific SSL flag in the connection URL.
  For example, jdbc:postgresql://myhost/db?ssl=true for postgres database.
</description>
</property>
<property>
	<name>javax.jdo.option.ConnectionDriverName</name>
	<value>com.mysql.jdbc.Driver</value>
	<description>User-Defined(Roy) Driver class name for a JDBC metastore</description>
</property>
<property>
	<name>javax.jdo.option.ConnectionUserName</name>
	<value>hive</value>
	<description>User-defined(Roy)Username to use against metastore database</description>
</property>
<property>
	<name>javax.jdo.option.ConnectionPassword</name>
	<value>hive</value>
	<description>User-defined(Roy)password to use against metastore database</description>
</property>
</configuration>


第三步:
schematool -dbType mysql -initSchema

```

### Running SPARK

```
/Users/wangwang/Downloads/spark-2.3.0-bin-hadoop2.7/sbin/start-all.sh
```

### Hive创建外部表的示例

```sql
CREATE EXTERNAL TABLE page_view_stg(viewTime INT, userid BIGINT,
                page_url STRING, referrer_url STRING,
                ip STRING COMMENT 'IP Address of the User',
                country STRING COMMENT 'country of origination')
COMMENT 'This is the staging page view table'
ROW FORMAT DELIMITED FIELDS TERMINATED BY '44' LINES TERMINATED BY '12' 
STORED AS TEXTFILE
LOCATION '/user/data/staging/page_view';
 
hadoop dfs -put /tmp/pv_2008-06-08.txt /user/data/staging/page_view
 
FROM page_view_stg pvs
INSERT OVERWRITE TABLE page_view PARTITION(dt='2008-06-08', country='US')
SELECT pvs.viewTime, pvs.userid, pvs.page_url, pvs.referrer_url, null, null, pvs.ip
WHERE pvs.country = 'US'
```

### Hive建表语法

```sql
CREATE [EXTERNAL] TABLE [IF NOT EXISTS] table_name
  [(col_name data_type [COMMENT col_comment], ...)]
  [COMMENT table_comment]
  [PARTITIONED BY (col_name data_type
    [COMMENT col_comment], ...)]
  [CLUSTERED BY (col_name, col_name, ...)
  [SORTED BY (col_name [ASC|DESC], ...)]
  INTO num_buckets BUCKETS]
  [ROW FORMAT row_format]
  [STORED AS file_format]
  [LOCATION hdfs_path]
```

### Hive外部表 并 导入json

```sql
ADD JAR /Users/wangwang/Downloads/hive/lib/hive-hcatalog-core-2.3.3.jar;

hadoop fs -mkdir /user/wangwang/test_table
hadoop fs -mkdir /user/wangwang/test_table/20180816
hadoop fs -put /Users/wangwang/Downloads/test.json /user/wangwang/test_table/20180816
hadoop fs -put /Users/wangwang/Downloads/test2.json /user/wangwang/test_table/20180816
hadoop fs -put -f /Users/wangwang/Downloads/test3.json /user/wangwang/test_table/dt=20180816

DROP TABLE test_table;

CREATE EXTERNAL TABLE test_table(id INT, amount INT,userId INT, createTime BIGINT)
PARTITIONED BY (dt INT)
ROW FORMAT SERDE 'org.apache.hive.hcatalog.data.JsonSerDe'
STORED AS TEXTFILE
LOCATION '/user/wangwang/test_table';

ALTER TABLE test_table ADD PARTITION (dt=20180816) LOCATION '/user/wangwang/test_table/20180816';

LOAD DATA INPATH '/user/wangwang/test_table/20180816/test.json' INTO TABLE test_table PARTITION(dt=20180816);

注意:
只要在分区文件夹下面的文件被更新,hive会自动加入到表里面,但是注意的是文件夹的名字是    "dt=20180816"  这种格式的

```

### Hive内部表

```sql
CREATE TABLE IF NOT EXISTS employee ( eid int, name String,
salary String, destination String)
ROW FORMAT DELIMITED
FIELDS TERMINATED BY '\t'
LINES TERMINATED BY '\n'
STORED AS TEXTFILE;

hadoop fs -mkdir /home
hadoop fs -put /Users/wangwang/Downloads/test.txt /home

LOAD DATA INPATH '/home/test.txt' INTO TABLE employee;
```

### Hive清除数据

```

**按分区删除:**

ALTER TABLE test1  DROP PARTITION (dt='2016-04-29');

**删除符合条件的数据:**

insert overwrite table t_table1 select * from t_table1 where XXXX;

其中xxx是你需要保留的数据的查询条件。

insert overwrite table tlog_bigtable  PARTITION (dt='2017-12-20',game_id = 'id')
select * from tlog_bigtable t
where t.dt = '2017-12-20'
and t.event_time < '2017-12-20 20:00:00'
and t.game_id = 'id'


**清空表：**

*insert overwrite table t_table1 select \* from t_table1 where 1=0;*

 *DROP TABLE [IF EXISTS] table_name  ;*

*TRUNCATE TABLE table_name*

```
### 学习一个脚本

```shell
https://www.cnblogs.com/raymoc/p/5321851.html
```

### hive和sparkSql结合:

```
第一步:
启动hive的元数据服务
hive可以通过服务的形式对外提供元数据读写操作，通过简单的配置即可
编辑 $HIVE_HOME/conf/hive-site.xml,增加如下内容:
<property>
<name>hive.metastore.uris</name>
<value>thrift://localhost:9083</value>
</property>

启动hive metastore
hive --service metastore  1>/dev/null  2>&1  &

第二步:
将 $HIVE_HOME/conf/hive-site.xml copy $SPARK_HOME/conf/
将 $HIVE_HOME/lib/mysql-connector-java-5.1.12.jar 拷贝到 $SPARK_HOME/lib/
(cp /Users/wangwang/.m2/repository/mysql/mysql-connector-java/5.1.39/mysql-connector-java-5.1.39.jar /Users/wangwang/Downloads/spark-2.3.0-bin-hadoop2.7/lib/)

第三步:

执行: spark-sql

给文件夹赋权:
hadoop fs -chmod -R 777 /tmp/hive 
sudo chmod -R 777 /tmp/hive
```

**使用程序和spark交互: **

配置spark-env.sh
export SPARK_MASTER_HOST=127.0.0.1

不然程序会报连接不上的错

![](https://ws4.sinaimg.cn/large/006tNbRwgy1fudpd767t3j31800ee74n.jpg)



http://localhost:8080/ sparkUI
