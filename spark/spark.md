### Driver

Driver进程的运行地点有如下两种：

1.driver进程运行在client端，对应用进行管理监控。(client模式)

2.Master节点指定某个Worker节点启动Driver进程，负责监控整个应用的执行。(cluster模式)

### client模式和cluster模式区别

deploy-mode client 和deploy-cluster 的区别就是，client必须在集群上的某个节点执行，所谓的客户端，也就是说提交应用程序的节点要作为整个程序运行的客户端，也就是说这个节点必须从属于集群！而cluster顾名思义，就是集群的意思，可以理解为提交的程序在某个集群运行，也就是说提交的机器只需要拥有单机版的spark环境就行了，至于提交的地方是哪里通过spark://指定就行了，提交的机器只作为提交的功能，提交完了之后就和他无关了！

# Spark计算过程分析

*摘要：* ### 基本概念----------Spark是一个分布式的内存计算框架，其特点是能处理大规模数据，计算速度快。Spark延续了Hadoop的MapReduce计算模型，相比之下Spark的计算过程保持在内存中，减少了硬盘读写，能够将多个操作进行合并后计算，因此提升了计算速度。同时Spark也提供了更丰富的计算API。MapReduce是Hadoop和Spark的计算模型，其特点

### 基本概念

------

Spark是一个分布式的内存计算框架，其特点是能处理大规模数据，计算速度快。Spark延续了Hadoop的MapReduce计算模型，相比之下Spark的计算过程保持在内存中，减少了硬盘读写，能够将多个操作进行合并后计算，因此提升了计算速度。同时Spark也提供了更丰富的计算API。

MapReduce是Hadoop和Spark的计算模型，其特点是Map和Reduce过程高度可并行化；过程间耦合度低，单个过程的失败后可以重新计算，而不会导致整体失败；最重要的是数据处理中的计算逻辑可以很好的转换为Map和Reduce操作。对于一个数据集来说，Map对每条数据做相同的转换操作，Reduce可以按条件对数据分组，然后在分组上做操作。除了Map和Reduce操作之外，Spark还延伸出了如filter，flatMap，count，distinct等更丰富的操作。

RDD的是Spark中最主要的数据结构，可以直观的认为RDD就是要处理的数据集。RDD是分布式的数据集，每个RDD都支持MapReduce类操作，经过MapReduce操作后会产生新的RDD，而不会修改原有RDD。RDD的数据集是分区的，因此可以把每个数据分区放到不同的分区上进行计算，而实际上大多数MapReduce操作都是在分区上进行计算的。Spark不会把每一个MapReduce操作都发起运算，而是尽量的把操作累计起来一起计算。Spark把操作划分为转换（transformation）和动作（action），对RDD进行的转换操作会叠加起来，直到对RDD进行动作操作时才会发起计算。这种特性也使Spark可以减少中间结果的吞吐，可以快速的进行多次迭代计算。

------

### 系统结构

------

Spark自身只对计算负责，其计算资源的管理和调度由第三方框架来实现。常用的框架有YARN和Mesos。本文以YARN为例进行介绍。先看一下Spark on YARN的系统结构图：

[![1](http://img4.tbcdn.cn/L1/461/1/a7a66827704f6503e2f59eb927728fef196c362c)](http://img4.tbcdn.cn/L1/461/1/a7a66827704f6503e2f59eb927728fef196c362c)

*Spark on YARN系统结构图*

图中共分为三大部分：Spark Driver， Worker， Cluster manager。其中Driver program负责将RDD转换为任务，并进行任务调度。Worker负责任务的执行。YARN负责计算资源的维护和分配。

Driver可以运行在用户程序中，或者运行在其中一个Worker上。Spark中的每一个应用（Application）对应着一个Driver。这个Driver可以接收RDD上的计算请求，每个动作（Action）类型的操作将被作为一个Job进行计算。Spark会根据RDD的依赖关系构建计算阶段（Stage）的有向无环图，每个阶段有与分区数相同的任务（Task）。这些任务将在每个分区（Partition）上进行计算，任务划分完成后Driver将任务提交到运行于Worker上的Executor中进行计算，并对任务的成功、失败进行记录和重启等处理。

Worker一般对应一台物理机，每个Worker上可以运行多个Executor，每个Executor都是独立的JVM进程，Driver提交的任务就是以线程的形式运行在Executor中的。如果使用YARN作为资源调度框架的话，其中一个Worker上还会有Executor launcher作为YARN的ApplicationMaster，用于向YARN申请计算资源，并启动、监测、重启Executor。

------

### 计算过程

------

这里我们从RDD到输出结果的整个计算过程为主线，探究Spark的计算过程。这个计算过程可以分为：

1. RDD构建：构建RDD之间的依赖关系，将RDD转换为阶段的有向无环图。
2. 任务调度：根据空闲计算资源情况进行任务提交，并对任务的运行状态进行监测和处理。
3. 任务计算：搭建任务运行环境，执行任务并返回任务结果。
4. Shuffle过程：两个阶段之间有宽依赖时，需要进行Shuffle操作。
5. 计算结果收集：从每个任务收集并汇总结果。

在这里我们用一个简洁的CharCount程序为例，这个程序把含有a-z字符的列表转化为RDD，对此RDD进行了Map和Reduce操作计算每个字母的频数，最后将结果收集。其代码如下：

[![0](http://img1.tbcdn.cn/L1/461/1/471127b6455841ce0d0d294ffd5c8678233a4c1f)](http://img1.tbcdn.cn/L1/461/1/471127b6455841ce0d0d294ffd5c8678233a4c1f)

*CharCount例子程序*

------

#### RDD构建和转换

------

RDD按照其作用可以分为两种类型，一种是对数据源的封装，可以把数据源转换为RDD，这种类型的RDD包括NewHadoopRDD，ParallelCollectionRDD，JdbcRDD等。另一种是对RDD的转换，从而实现一种计算方法，这种类型的RDD包括MappedRDD，ShuffledRDD，FilteredRDD等。数据源类型的RDD不依赖于其他RDD，计算类的RDD拥有自己的RDD依赖。

RDD有三个要素：分区，依赖关系，计算逻辑。分区是保证RDD分布式的特性，分区可以对RDD的数据进行划分，划分后的分区可以分布到不同的Executor中，大部分对RDD的计算都是在分区上进行的。依赖关系维护着RDD的计算过程，每个计算类型的RDD在计算时，会将所依赖的RDD作为数据源进行计算。根据一个分区的输出是否被多分区使用，Spark还将依赖分为窄依赖和宽依赖。RDD的计算逻辑是其功能的体现，其计算过程是以所依赖的RDD为数据源进行的。

例子中共产生了三个RDD，除了第一个RDD之外，每个RDD与上级RDD有依赖关系。

1. spark.parallelize(data, partitionSize)方法将产生一个数据源型的ParallelCollectionRDD，这个RDD的分区是对列表数据的切分，没有上级依赖，计算逻辑是直接返回分区数据。

2. map函数将会创建一个MappedRDD，其分区与上级依赖相同，会有一个依赖于ParallelCollectionRDD的窄依赖，计算逻辑是对ParallelCollectionRDD的数据做map操作。

3. reduceByKey函数将会产生一个ShuffledRDD，分区数量与上面的MappedRDD相同，会有一个依赖于MappedRDD的宽依赖，计算逻辑是Shuffle后在分区上的聚合操作。

   [![2](http://img1.tbcdn.cn/L1/461/1/2848b03e16e14a0e277c66a6aac59236a81627fa)](http://img1.tbcdn.cn/L1/461/1/2848b03e16e14a0e277c66a6aac59236a81627fa)

*RDD的依赖关系*

Spark在遇到动作类操作时，就会发起计算Job，把RDD转换为任务，并发送任务到Executor上执行。从RDD到任务的转换过程是在DAGScheduler中进行的。其总体思路是根据RDD的依赖关系，把窄依赖合并到一个阶段中，遇到宽依赖则划分出新的阶段，最终形成一个阶段的有向无环图，并根据图的依赖关系先后提交阶段。每个阶段按照分区数量划分为多个任务，最终任务被序列化并提交到Executor上执行。

[![3](http://img3.tbcdn.cn/L1/461/1/542a304efc946068cee4bd0c5785184cbf9aa39b)](http://img3.tbcdn.cn/L1/461/1/542a304efc946068cee4bd0c5785184cbf9aa39b)

*RDD到Task的构建过程*

当RDD的动作类操作被调用时，RDD将调用SparkContext开始提交Job，SparkContext将调用DAGScheduler把RDD转化为阶段的有向无环图，然后首先将有向无环图中没有未完成的依赖的阶段进行提交。在阶段被提交时，每个阶段将产生与分区数量相同的任务，这些任务称之为一个TaskSet。任务的类型分为 ShuffleMapTask和ResultTask，如果阶段的输出将用于下个阶段的输入，也就是需要进行Shuffle操作，则任务类型为ShuffleMapTask。如果阶段的输入即为Job结果，则任务类型为ResultTask。任务创建完成后会交给TaskSchedulerImpl进行TaskSet级别的调度执行。

------

#### 任务调度

------

在任务调度的分工上，DAGScheduler负责总体的任务调度，SchedulerBackend负责与Executors通信，维护计算资源信息，并负责将任务序列化并提交到Executor。TaskSetManager负责对一个阶段的任务进行管理，其中会根据任务的数据本地性选择优先提交的任务。TaskSchedulerImpl负责对TaskSet进行调度，通过调度策略确定TaskSet优先级。同时是一个中介者，其将DAGScheduler，SchedulerBackend和TaskSetManager联结起来，对Executor和Task的相关事件进行转发。

在任务提交流程上，DAGScheduler提交TaskSet到TaskSchedulerImpl，使TaskSet在此注册。TaskSchedulerImpl通知SchedulerBackend有新的任务进入，SchedulerBackend调用makeOffers根据注册到自己的Executors信息，确定是否有计算资源执行任务，如有资源则通知TaskSchedulerImpl去分配这些资源。 TaskSchedulerImpl根据TaskSet调度策略优先分配TaskSet接收此资源。TaskSetManager再根据任务的数据本地性，确定提交哪些任务。最终任务的闭包被SchedulerBackend序列化，并传输给Executor进行执行。

[![4](http://img2.tbcdn.cn/L1/461/1/34637601256f43c6e885a5906b593293ea4f23ba)](http://img2.tbcdn.cn/L1/461/1/34637601256f43c6e885a5906b593293ea4f23ba)

*Spark的任务调度*

根据以上过程，Spark中的任务调度实际上分了三个层次。第一层次是基于阶段的有向无环图进行Stage的调度，第二层次是根据调度策略（FIFO，FAIR）进行TaskSet调度，第三层次是根据数据本地性（Process，Node，Rack）在TaskSet内进行调度。

------

#### 任务计算

------

任务的计算过程是在Executor上完成的，Executor监听来自SchedulerBackend的指令，接收到任务时会启动TaskRunner线程进行任务执行。在TaskRunner中首先将任务和相关信息反序列化，然后根据相关信息获取任务所依赖的Jar包和所需文件，完成准备工作后执行任务的run方法，实际上就是执行ShuffleMapTask或ResultTask的run方法。任务执行完毕后将结果发送给Driver进行处理。

在Task.run方法中可以看到ShuffleMapTask和ResultTask有着不同的计算逻辑。ShuffleMapTask是将所依赖RDD的输出写入到ShuffleWriter中，为后面的Shuffle过程做准备。ResultTask是在所依赖RDD上应用一个函数，并返回函数的计算结果。在这两个Task中只能看到数据的输出方式，而看不到应有的计算逻辑。实际上计算过程是包含在RDD中的，调用RDD. Iterator方法获取RDD的数据将触发这个RDD的计算动作（RDD. Iterator），由于此RDD的计算过程中也会使用所依赖RDD的数据。从而RDD的计算过程将递归向上直到一个数据源类型的RDD，再递归向下计算每个RDD的值。需要注意的是，以上的计算过程都是在分区上进行的，而不是整个数据集，计算完成得到的是此分区上的结果，而不是最终结果。

从RDD的计算过程可以看出，RDD的计算过程是包含在RDD的依赖关系中的，只要RDD之间是连续窄依赖，那么多个计算过程就可以在同一个Task中进行计算，中间结果可以立即被下个操作使用，而无需在进程间、节点间、磁盘上进行交换。

[![5](http://img2.tbcdn.cn/L1/461/1/699d9b6d54a039fe833a7efb66a414153cbe47d6)](http://img2.tbcdn.cn/L1/461/1/699d9b6d54a039fe833a7efb66a414153cbe47d6)

*RDD计算过程*

------

#### Shuffle过程

------

Shuffle是一个对数据进行分组聚合的操作过程，原数据将按照规则进行分组，然后使用一个聚合函数应用于分组上，从而产生新数据。Shuffle操作的目的是把同组数据分配到相同分区上，从而能够在分区上进行聚合计算。为了提高Shuffle性能，还可以先在原分区对数据进行聚合（mapSideCombine），然后再分配部分聚合的数据到新分区，第三步在新分区上再次进行聚合。

在划分阶段时，只有遇到宽依赖才会产生新阶段，才需要Shuffle操作。宽依赖与窄依赖取决于原分区被新分区的使用关系，只要一个原分区会被多个新分区使用，则为宽依赖，需要Shuffle。否则为窄依赖，不需要Shuffle。

以上也就是说只有阶段与阶段之间需要Shuffle，最后一个阶段会输出结果，因此不需要Shuffle。例子中的程序会产生两个阶段，第一个我们简称Map阶段，第二个我们简称Reduce阶段。Shuffle是通过Map阶段的ShuffleMapTask与Reduce阶段的ShuffledRDD配合完成的。其中ShuffleMapTask会把任务的计算结果写入ShuffleWriter，ShuffledRDD从ShuffleReader中读取数据，Shuffle过程会在写入和读取过程中完成。以HashShuffle为例，HashShuffleWriter在写入数据时，会决定是否在原分区做聚合，然后根据数据的Hash值写入相应新分区。HashShuffleReader再根据分区号取出相应数据，然后对数据进行聚合。

[![6](http://img3.tbcdn.cn/L1/461/1/502d6d174c90bdf22206a0ad4159540fee033e81)](http://img3.tbcdn.cn/L1/461/1/502d6d174c90bdf22206a0ad4159540fee033e81)

*Spark的Shuffle过程*

------

#### 计算结果收集

------

ResultTask任务计算完成后可以得到每个分区的计算结果，此时需要在Driver上对结果进行汇总从而得到最终结果。

RDD在执行collect，count等动作时，会给出两个函数，一个函数在分区上执行，一个函数在分区结果集上执行。例如collect动作在分区上（Executor中）执行将Iterator转换为Array的函数，并将此函数结果返回到Driver。Driver 从多个分区上得到Array类型的分区结果集，然后在结果集上（Driver中）执行合并Array的操作，从而得到最终结果。

------

### 总结

------

Spark对于RDD的设计是其精髓所在。用RDD操作数据的感觉就一个字：爽！。想到RDD背后是几吨重的大数据集，而我们随手调用下map(), reduce()就可以把它转换来转换去，一种半两拨千斤的感觉就会油然而生。我想是以下特性给我们带来了这些：

1. RDD把不同来源，不同类型的数据进行了统一，使我们面对RDD的时候就会产生一种信心，就会认为这是某种类型的RDD，从而可以进行RDD的所有操作。
2. 对RDD的操作可以叠加到一起计算，我们不必担心中间结果吞吐对性能的影响。
3. RDD提供了更丰富的数据集操作函数，这些函数大都是在MapReduce基础上扩充的，使用起来很方便。
4. RDD为提供了一个简洁的编程界面，背后复杂的分布式计算过程对开发者是透明的。从而能够让我们把关注点更多的放在业务上。





# spark提交过程分析（standalone模式）

### 一、构造SparkContext

1.1. 在shell下，通过spark-submit命令将Application提交到集群,此时spark会通过反射的方式，创建和构造一个DriverActor进程出来（scala中的actor类似java的多线程）
 1.2. Driver进程会执行我们提交的Application应用程序，一般情况下，先构造SparkConf,再构造SparkContext
 1.3. SparkContext在初始化的时候，最主要的做的就是构造DAGScheduler和TaskScheduler。
 1.4. TaskScheduler实际上，是会负责，通过它对应的一个后台进程，去连接Spark集群的Master进程注册Application,
 1.5. Master接收到Application的注册请求后，会使用自己的资源调度算法（基于调度器standalone,Yarn,Mesos等都有不同的调度算法），在Spark集群的Worker上为这个Application启动Executor
 1.6. Master通知worker启动Executor后，Worker会为Application启动Executor进程，
 1.7. Executor启动之后，首先做的就是会将自己反向注册到TaskScheduler上去，到此为止SparkContext完成了初始化。

### 二、运行Application

2.1. 所有Executor都反向注册到Driver上之后，Driver结束SparkContext初始化，会继续执行我们编写的代码
 2.2. 每执行一个Action就会创建一个job，job会提交给DAGScheduler
 2.3 DAGScheduler会采用自己的stage划分算法将job划分为多个stage，然后每个stage创建一个TaskSet，在此过程中，stage划分算法非常重要，后续会进行详细研究。
 2.4 DAGScheduler会将TaskSet传递给TaskScheduler，TaskScheduler会把TaskSet里每一个task提交到Executor上执行（task分配算法）
 2.5 Executor每接收一个task都会用TaskRunner来封装task，然后从线程池里面取出一个线程，执行这个task，TaskRunner将我们编写的代码，也就是要执行的算子以及函数，拷贝，反序列化，然后执行Task。
 2.6 Task有两种，ShuffleMapTask和ResultTask。只有最后一个stage是ResultTask，之前的stage,都是ShuffleMapTask.
 2.7 所以，最后整个Spark应用程序的执行，就是将stage分批次作为taskset提交给executor执行，每个task针对RDD的一个parktition，执行我们定义的算子和函数，以此类推，直到所有操作执行完为止。

 

 

 .spark-submit参数说明

使用spark-submit提交spark作业的时候有许多参数可供我们选择，这些参数有的用于作业优化，有的用于实现某些功能，所有的参数列举如下：

| 参数                  | 说明                                                         |
| --------------------- | ------------------------------------------------------------ |
| –master               | 集群的master地址。如：spark://host:port，mesos://host:port， yarn-client，yarn-cluster，local[k]本地以k个worker线程执行， k一般为cpu的内核数，local[*]以尽可能多的线程数执行。 |
| –deploy-mode          | driver运行的模式，client或者cluster模式，默认为client        |
| –class                | 应用程序的主类（用于Java或者Scala应用）                      |
| –name                 | 应用程序的名称                                               |
| –jars                 | 作业执行过程中使用到的其他jar，可以使用逗号分隔添加多个。可以使用如下方式添加： file：指定http文件服务器的地址，每个executor都从这个地址下载。 hdfs,http,https,ftp:从以上协议指定的路径下载。 local:直接从当前的worker节点下载。 |
| –packages             | 从maven添加作业执行过程中使用到的包，查找顺序先本地仓库再远程仓库。 可以添加多个，每个的格式为：groupId:artifactId:version |
| –exclude-packages     | 需要排除的包，可以为多个，使用逗号分隔。                     |
| –repositories         | 远程仓库。可以添加多个，逗号分隔。                           |
| –py-files             | 逗号分隔的”.zip”,”.egg”或者“.py”文件，这些文件放在python app的PYTHONPATH下面 |
| –files                | 逗号分隔的文件列表，这些文件放在每个executor的工作目录下。   |
| –conf                 | 其他额外的spark配置属性。                                    |
| –properties-file      | 指向一个配置文件，通过这个文件可以加载额外的配置。 如果没有则会查找conf/spark-defaults.conf |
| –driver-memory        | driver节点的内存大小。如2G，默认为1024M。                    |
| –driver-java-options  | 作用于driver的额外java配置项。                               |
| –driver-library-path  | 作用于driver的外部lib包。                                    |
| –driver-class-path    | 作用于driver的额外类路径，使用–jar时会自动添加路径。         |
| –executor-memory      | 每个excutor的执行内存。                                      |
| –proxy-user           | 提交作业的模拟用户。是hadoop中的一种安全机制，具体可以参考: http://dongxicheng.org/mapreduce-nextgen/hadoop-secure-impersonation/ |
| –verbose              | 打印debug信息。                                              |
| –version              | 打印当前spark的版本。                                        |
| –driver-cores         | driver的内核数，默认为1。**（仅用于spark standalone集群中）** |
| –superivse            | driver失败时重启 **（仅用于spark standalone或者mesos集群中）** |
| –kill                 | kill指定的driver **（仅用于spark standalone或者mesos集群中）** |
| –total-executor-cores | 给所有executor的所有内核数。**（仅用于spark standalone或者mesos集群中）** |
| –executor-cores       | 分配给每个executor的内核数。**（仅用于spark standalone或者yarn集群中）** |
| –driver-cores         | driver的内核数。**（仅yarn）**                               |
| –queue                | 作业执行的队列。**（仅yarn）**                               |
| –num-executor         | executor的数量。**（仅yarn）**                               |
| –archives             | 需要添加到executor执行目录下的归档文件列表，逗号分隔。**（仅yarn）** |
| — principal           | 运行于secure hdfs时用于登录到KDC的principal。**（仅yarn）**  |
| –keytab               | 包含keytab文件的全路径。**（仅yarn）**                       |

 

 



 







