package com.wayrecall.tracker.storage

import zio.*
import zio.json.*
import org.apache.kafka.clients.producer.{KafkaProducer => JavaKafkaProducer, ProducerConfig, ProducerRecord, RecordMetadata, Callback}
import org.apache.kafka.common.serialization.StringSerializer
import com.wayrecall.tracker.domain.{GpsPoint, DeviceStatus, KafkaError}
import com.wayrecall.tracker.config.KafkaConfig
import java.util.Properties

/**
 * Kafka Producer - чисто функциональный интерфейс
 */
trait KafkaProducer:
  def publish(topic: String, key: String, value: String): IO[KafkaError, Unit]
  def publishGpsEvent(point: GpsPoint): IO[KafkaError, Unit]
  def publishDeviceStatus(status: DeviceStatus): IO[KafkaError, Unit]

object KafkaProducer:
  
  // Accessor методы для ZIO service pattern
  def publish(topic: String, key: String, value: String): ZIO[KafkaProducer, KafkaError, Unit] =
    ZIO.serviceWithZIO(_.publish(topic, key, value))
  
  def publishGpsEvent(point: GpsPoint): ZIO[KafkaProducer, KafkaError, Unit] =
    ZIO.serviceWithZIO(_.publishGpsEvent(point))
  
  def publishDeviceStatus(status: DeviceStatus): ZIO[KafkaProducer, KafkaError, Unit] =
    ZIO.serviceWithZIO(_.publishDeviceStatus(status))
  
  /**
   * Live реализация с Java Kafka Producer
   */
  final case class Live(
      producer: JavaKafkaProducer[String, String],
      config: KafkaConfig
  ) extends KafkaProducer:
    
    override def publish(topic: String, key: String, value: String): IO[KafkaError, Unit] =
      ZIO.async { callback =>
        val record = new ProducerRecord[String, String](topic, key, value)
        producer.send(record, (metadata: RecordMetadata, exception: Exception) =>
          if exception != null then
            callback(ZIO.fail(KafkaError.ProducerError(exception.getMessage)))
          else
            callback(ZIO.unit)
        )
      }
    
    override def publishGpsEvent(point: GpsPoint): IO[KafkaError, Unit] =
      serializeAndPublish(point, config.topics.rawGpsEvents, point.vehicleId.toString)
    
    override def publishDeviceStatus(status: DeviceStatus): IO[KafkaError, Unit] =
      serializeAndPublish(status, config.topics.deviceStatus, status.imei)
    
    /**
     * Сериализует объект в JSON и публикует - чисто функциональный подход
     */
    private def serializeAndPublish[A: JsonEncoder](
      value: A,
      topic: String,
      key: String
    ): IO[KafkaError, Unit] =
      ZIO.attempt(value.toJson)
        .mapError(e => KafkaError.SerializationError(e.getMessage))
        .flatMap(json => publish(topic, key, json))
  
  /**
   * ZIO Layer с управлением ресурсами
   */
  val live: ZLayer[KafkaConfig, Throwable, KafkaProducer] =
    ZLayer.scoped {
      for
        config <- ZIO.service[KafkaConfig]
        
        // Создаем properties для Kafka producer
        props = {
          val p = new Properties()
          p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers)
          p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
          p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
          p.put(ProducerConfig.ACKS_CONFIG, config.producer.acks)
          p.put(ProducerConfig.RETRIES_CONFIG, config.producer.retries.toString)
          p.put(ProducerConfig.BATCH_SIZE_CONFIG, config.producer.batchSize.toString)
          p.put(ProducerConfig.LINGER_MS_CONFIG, config.producer.lingerMs.toString)
          p.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, config.producer.compressionType)
          p
        }
        
        // Создаем producer с автоматическим закрытием
        producer <- ZIO.acquireRelease(
          ZIO.attempt(new JavaKafkaProducer[String, String](props))
            .tap(_ => ZIO.logInfo(s"Kafka producer создан: ${config.bootstrapServers}"))
        )(prod => 
          ZIO.attempt(prod.close()).orDie
            .tap(_ => ZIO.logInfo("Kafka producer закрыт"))
        )
      yield Live(producer, config)
    }
