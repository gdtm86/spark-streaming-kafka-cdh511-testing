import kafka.utils.ZkUtils
import org.apache.hadoop.hbase.filter.PrefixFilter
import org.apache.hadoop.hbase.util.Bytes
import org.apache.hadoop.hbase.{TableName, HBaseConfiguration}
import org.apache.hadoop.hbase.client.{Scan, Put, ConnectionFactory}
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.spark.streaming.kafka010.ConsumerStrategies._
import org.apache.spark.streaming.kafka010.{OffsetRange, HasOffsetRanges, KafkaUtils}
import org.apache.spark.streaming.kafka010.LocationStrategies._
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.apache.spark.{SparkContext, SparkConf}



/**
 * Created by gmedasani on 6/10/17.
 */
object KafkaOffsetsBlogStreamingDriver {

  def main(args: Array[String]) {

    if (args.length < 6) {
      System.err.println("Usage: KafkaDirectStreamTest <batch-duration-in-seconds> <kafka-bootstrap-servers> " +
        "<kafka-topics> <kafka-consumer-group-id> <hbase-table-name> <kafka-zookeeper-quorum>")
      System.exit(1)
    }

    val batchDuration = args(0)
    val bootstrapServers = args(1).toString
    val topicsSet = args(2).toString.split(",").toSet
    val consumerGroupID = args(3)
    val hbaseTableName = args(4)
    val zkQuorum = args(5)
    val zkKafkaRootDir = "kafka"
    val zkSessionTimeOut = 10000
    val zkConnectionTimeOut = 10000

    val sparkConf = new SparkConf().setAppName("Kafka-Offset-Management-Blog")
                                  .setMaster("local[4]")//Uncomment this line to test while developing on a workstation
    val sc = new SparkContext(sparkConf)
    val ssc = new StreamingContext(sc, Seconds(batchDuration.toLong))
    val topics = topicsSet.toArray
    val topic = topics(0)

    val kafkaParams = Map[String, Object](
      "bootstrap.servers" -> bootstrapServers,
      "key.deserializer" -> classOf[StringDeserializer],
      "value.deserializer" -> classOf[StringDeserializer],
      "group.id" -> consumerGroupID,
      "auto.offset.reset" -> "earliest",
      "enable.auto.commit" -> (false: java.lang.Boolean)
    )

    /*
    Create a dummy process that simply returns the message as is.
     */
    def processMessage(message:ConsumerRecord[String,String]):ConsumerRecord[String,String]={
      message
    }

    /*
    Save Offsets into HBase
     */
    def saveOffsets(TOPIC_NAME:String,GROUP_ID:String,offsetRanges:Array[OffsetRange],hbaseTableName:String,
                    batchTime: org.apache.spark.streaming.Time) ={
      val hbaseConf = HBaseConfiguration.create()
      hbaseConf.addResource("src/main/resources/hbase-site.xml")
      val conn = ConnectionFactory.createConnection(hbaseConf)
      val table = conn.getTable(TableName.valueOf(hbaseTableName))
      val rowKey = TOPIC_NAME + ":" + GROUP_ID + ":" + String.valueOf(batchTime.milliseconds)
      val put = new Put(rowKey.getBytes)
      for(offset <- offsetRanges){
        put.addColumn(Bytes.toBytes("offsets"),Bytes.toBytes(offset.partition.toString),
          Bytes.toBytes(offset.untilOffset.toString))
      }
      table.put(put)
      conn.close()
    }

    /*
    Returns last committed offsets for all the partitions of a given topic from HBase in following cases.
      - CASE 1: SparkStreaming job is started for the first time. This function gets the number of topic partitions from
        Zookeeper and for each partition returns the last committed offset as 0
      - CASE 2: SparkStreaming is restarted and there are no changes to the number of partitions in a topic. Last
        committed offsets for each topic-partition is returned as is from HBase.
      - CASE 3: SparkStreaming is restarted and the number of partitions in a topic increased. For old partitions, last
        committed offsets for each topic-partition is returned as is from HBase as is. For newly added partitions,
        function returns last committed offsets as 0
     */
    def getLastCommittedOffsets(TOPIC_NAME:String,GROUP_ID:String,hbaseTableName:String,zkQuorum:String,
                                zkRootDir:String, sessionTimeout:Int,connectionTimeOut:Int):Map[TopicPartition,Long] ={

      val hbaseConf = HBaseConfiguration.create()
      hbaseConf.addResource("src/main/resources/hbase-site.xml")
      val zkUrl = zkQuorum+"/"+zkRootDir
      val zkClientAndConnection = ZkUtils.createZkClientAndConnection(zkUrl,sessionTimeout,connectionTimeOut)
      val zkUtils = new ZkUtils(zkClientAndConnection._1, zkClientAndConnection._2,false)
      val zKNumberOfPartitionsForTopic = zkUtils.getPartitionsForTopics(Seq(TOPIC_NAME)).get(TOPIC_NAME).toList.head.size

      //Connect to HBase to retrieve last committed offsets
      val conn = ConnectionFactory.createConnection(hbaseConf)
      val table = conn.getTable(TableName.valueOf(hbaseTableName))
      val startRow = TOPIC_NAME + ":" + GROUP_ID + ":" + String.valueOf(System.currentTimeMillis())
      val stopRow = TOPIC_NAME + ":" + GROUP_ID + ":" + 0
      val scan = new Scan()
      val scanner = table.getScanner(scan.setStartRow(startRow.getBytes).setStopRow(stopRow.getBytes).setReversed(true))
      val result = scanner.next()

      var hbaseNumberOfPartitionsForTopic = 0 //Set the number of partitions discovered for a topic in HBase to 0
      if (result != null){
        //If the result from hbase scanner is not null, set number of partitions from hbase to the number of cells
        hbaseNumberOfPartitionsForTopic = result.listCells().size()
      }

      val fromOffsets = collection.mutable.Map[TopicPartition,Long]()

      if(hbaseNumberOfPartitionsForTopic == 0){
        // initialize fromOffsets to beginning
          for (partition <- 0 to zKNumberOfPartitionsForTopic-1){
            fromOffsets += (new TopicPartition(TOPIC_NAME,partition) -> 0)}
      } else if(zKNumberOfPartitionsForTopic > hbaseNumberOfPartitionsForTopic){
        // handle scenario where new partitions have been added to existing kafka topic
          for (partition <- 0 to hbaseNumberOfPartitionsForTopic-1){
            val fromOffset = Bytes.toString(result.getValue(Bytes.toBytes("offsets"),Bytes.toBytes(partition.toString)))
            fromOffsets += (new TopicPartition(TOPIC_NAME,partition) -> fromOffset.toLong)}
          for (partition <- hbaseNumberOfPartitionsForTopic to zKNumberOfPartitionsForTopic-1){
            fromOffsets += (new TopicPartition(TOPIC_NAME,partition) -> 0)}
      } else {
        //initialize fromOffsets from last run
          for (partition <- 0 to hbaseNumberOfPartitionsForTopic-1 ){
            val fromOffset = Bytes.toString(result.getValue(Bytes.toBytes("offsets"),Bytes.toBytes(partition.toString)))
            fromOffsets += (new TopicPartition(TOPIC_NAME,partition) -> fromOffset.toLong)}
      }
      scanner.close()
      conn.close()
      fromOffsets.toMap
    }


    val fromOffsets= getLastCommittedOffsets(topic,consumerGroupID,hbaseTableName,zkQuorum,zkKafkaRootDir,
      zkSessionTimeOut,zkConnectionTimeOut)
    val inputDStream = KafkaUtils.createDirectStream[String, String](ssc,PreferConsistent,Assign[String, String](
      fromOffsets.keys,kafkaParams,fromOffsets))

    /*
      For each RDD in a DStream apply a map transformation that processes the message.
    */
    inputDStream.foreachRDD((rdd,batchTime) => {
      val offsetRanges = rdd.asInstanceOf[HasOffsetRanges].offsetRanges
      offsetRanges.foreach(offset => println(offset.topic, offset.partition, offset.fromOffset,offset.untilOffset))
      val newRDD = rdd.map(message => processMessage(message))
      newRDD.count()
      saveOffsets(topic,consumerGroupID,offsetRanges,hbaseTableName,batchTime) //save the offsets to HBase
    })

    println("Number of messages processed " + inputDStream.count())
    ssc.start()
    ssc.awaitTermination()
  }
}
