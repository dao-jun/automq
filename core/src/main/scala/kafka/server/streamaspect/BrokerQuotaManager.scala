/*
 * Copyright 2024, AutoMQ HK Limited.
 *
 * The use of this file is governed by the Business Source License,
 * as detailed in the file "/LICENSE.S3Stream" included in this repository.
 *
 * As of the Change Date specified in that file, in accordance with
 * the Business Source License, use of this software will be governed
 * by the Apache License, Version 2.0
 */

package kafka.server.streamaspect

import kafka.network.RequestChannel
import kafka.server._
import kafka.utils.QuotaUtils
import org.apache.kafka.common.MetricName
import org.apache.kafka.common.metrics.stats.{Avg, CumulativeSum, Rate}
import org.apache.kafka.common.metrics.{Metrics, Quota, QuotaViolationException, Sensor}
import org.apache.kafka.common.security.auth.KafkaPrincipal
import org.apache.kafka.common.utils.Time
import org.apache.kafka.network.Session
import org.apache.kafka.server.config.BrokerQuotaManagerConfig

import java.util.Properties
import scala.collection.mutable
import scala.jdk.CollectionConverters._

class BrokerQuotaManager(private val config: BrokerQuotaManagerConfig,
  private val metrics: Metrics,
  private val time: Time,
  private val threadNamePrefix: String)
  extends ClientRequestQuotaManager(config, metrics, time, threadNamePrefix, None) {
  private val metricsTags = Map("domain" -> "broker", "nodeId" -> String.valueOf(config.nodeId()))
  private val whiteListCache = mutable.HashMap[String, Boolean]()

  private val brokerDelayQueueSensor: Sensor = metrics.sensor("broker-delayQueue")
  brokerDelayQueueSensor.add(metrics.metricName("broker-queue-size", "",
    "Tracks the size of the delay queue"), new CumulativeSum())

  override def delayQueueSensor: Sensor = brokerDelayQueueSensor

  def getMaxValueInQuotaWindow(quotaType: QuotaType): Double = {
    if (config.quotaEnabled) {
      quotaLimit(quotaType)
    } else {
      Double.MaxValue
    }
  }

  def recordNoThrottle(quotaType: QuotaType, value: Double): Unit = {
    val clientSensors = getOrCreateQuotaSensors(quotaType)
    clientSensors.quotaSensor.record(value, time.milliseconds(), false)
  }

  def maybeRecordAndGetThrottleTimeMs(quotaType: QuotaType, request: RequestChannel.Request, value: Double,
    timeMs: Long): Int = {
    if (!config.quotaEnabled) {
      // Quota is disabled, no need to throttle
      return 0
    }

    if (isInWhiteList(request.session.principal, request.context.clientId(), request.context.listenerName())) {
      // Client is in the white list, no need to throttle
      return 0
    }

    maybeRecordAndGetThrottleTimeMs(quotaType, value, timeMs)
  }

  protected def throttleTime(quotaType: QuotaType, e: QuotaViolationException, timeMs: Long): Long = {
    quotaType match {
      case QuotaType.SlowFetch | QuotaType.RequestRate =>
        QuotaUtils.boundedThrottleTime(e, maxThrottleTimeMs, timeMs)
      case _ =>
        QuotaUtils.throttleTime(e, timeMs)
    }

  }

  private def isInWhiteList(principal: KafkaPrincipal, clientId: String, listenerName: String): Boolean = {
    val key = s"$principal:$clientId:$listenerName"
    whiteListCache.get(key) match {
      case Some(isWhiteListed) => isWhiteListed
      case None =>
        val isWhiteListed = (principal.getPrincipalType == KafkaPrincipal.USER_TYPE && config.userWhiteList().contains(principal.getName)) ||
          config.clientIdWhiteList().contains(clientId) ||
          config.listenerWhiteList().contains(listenerName)
        whiteListCache.put(clientId, isWhiteListed)
        isWhiteListed
    }
  }

  def maybeRecordAndGetThrottleTimeMs(quotaType: QuotaType, value: Double, timeMs: Long): Int = {
    val clientSensors = getOrCreateQuotaSensors(quotaType)
    try {
      clientSensors.quotaSensor.record(value, timeMs, true)
      0
    } catch {
      case e: QuotaViolationException =>
        val throttleTimeMs = throttleTime(quotaType, e, timeMs).toInt
        debug(s"Quota violated for sensor (${clientSensors.quotaSensor.name}). Delay time: ($throttleTimeMs)")
        throttleTimeMs
    }
  }

  def unrecordQuotaSensor(quotaType: QuotaType, value: Double, timeMs: Long): Unit = {
    val clientSensors = getOrCreateQuotaSensors(quotaType)
    clientSensors.quotaSensor.record(value * (-1), timeMs, false)
  }

  def updateQuotaConfigs(properties: Option[Properties] = None): Unit = {
    if (properties.isDefined) {
      config.update(properties.get)
      whiteListCache.clear()

      if (!config.quotaEnabled) {
        metrics.removeSensor(getQuotaSensorName(QuotaType.RequestRate, metricsTags))
        metrics.removeSensor(getQuotaSensorName(QuotaType.Produce, metricsTags))
        metrics.removeSensor(getQuotaSensorName(QuotaType.Fetch, metricsTags))
        metrics.removeSensor(getQuotaSensorName(QuotaType.SlowFetch, metricsTags))
        metrics.removeSensor(getThrottleTimeSensorName(QuotaType.RequestRate, metricsTags))
        metrics.removeSensor(getThrottleTimeSensorName(QuotaType.Produce, metricsTags))
        metrics.removeSensor(getThrottleTimeSensorName(QuotaType.Fetch, metricsTags))
        metrics.removeSensor(getThrottleTimeSensorName(QuotaType.SlowFetch, metricsTags))
        return
      }

      val allMetrics = metrics.metrics()

      val requestRateMetric = allMetrics.get(clientQuotaMetricName(QuotaType.RequestRate, metricsTags))
      if (requestRateMetric != null) {
        requestRateMetric.config(getQuotaMetricConfig(quotaLimit(QuotaType.RequestRate)))
      }

      val produceMetric = allMetrics.get(clientQuotaMetricName(QuotaType.Produce, metricsTags))
      if (produceMetric != null) {
        produceMetric.config(getQuotaMetricConfig(quotaLimit(QuotaType.Produce)))
      }

      val fetchMetric = allMetrics.get(clientQuotaMetricName(QuotaType.Fetch, metricsTags))
      if (fetchMetric != null) {
        fetchMetric.config(getQuotaMetricConfig(quotaLimit(QuotaType.Fetch)))
      }

      val slowFetchMetric = allMetrics.get(clientQuotaMetricName(QuotaType.SlowFetch, metricsTags))
      if (slowFetchMetric != null) {
        slowFetchMetric.config(getQuotaMetricConfig(quotaLimit(QuotaType.SlowFetch)))
      }
    }
  }

  def updateQuota(quotaType: QuotaType, quota: Double): Unit = {
    // update the quota in the config first to make sure the new quota will be used if {@link #updateQuotaMetricConfigs} is called
    quotaType match {
      case QuotaType.RequestRate => config.requestRateQuota(quota)
      case QuotaType.Produce => config.produceQuota(quota)
      case QuotaType.Fetch => config.fetchQuota(quota)
      case QuotaType.SlowFetch => config.slowFetchQuota(quota)
      case _ => throw new IllegalArgumentException(s"Unknown quota type $quotaType")
    }

    // update the metric config
    val allMetrics = metrics.metrics()
    val metric = allMetrics.get(clientQuotaMetricName(quotaType, metricsTags))
    if (metric != null) {
      metric.config(getQuotaMetricConfig(quotaLimit(quotaType)))
    }
  }

  def throttle(
    quotaType: QuotaType,
    throttleCallback: ThrottleCallback,
    throttleTimeMs: Int
  ): Unit = {
    if (throttleTimeMs > 0) {
      val clientSensors = getOrCreateQuotaSensors(quotaType)
      clientSensors.throttleTimeSensor.record(throttleTimeMs)
      val throttledChannel = new ThrottledChannel(time, throttleTimeMs, throttleCallback)
      delayQueue.add(throttledChannel)
      delayQueueSensor.record()
      debug("Channel throttled for sensor (%s). Delay time: (%d)".format(clientSensors.quotaSensor.name(), throttleTimeMs))
    }
  }

  private def getThrottleTimeSensorName(quotaType: QuotaType, metricTags: Map[String, String]): String =
    s"${quotaType}ThrottleTime-${metricTagsToSensorSuffix(metricTags)}"

  private def getQuotaSensorName(quotaType: QuotaType, metricTags: Map[String, String]): String =
    s"$quotaType-${metricTagsToSensorSuffix(metricTags)}"

  def quotaLimit(quotaType: QuotaType): Double = {
    quotaType match {
      case QuotaType.RequestRate => config.requestRateQuota
      case QuotaType.Produce => config.produceQuota
      case QuotaType.Fetch => config.fetchQuota
      case QuotaType.SlowFetch => config.slowFetchQuota
      case _ => throw new IllegalArgumentException(s"Unknown quota type $quotaType")
    }
  }

  protected def clientQuotaMetricName(quotaType: QuotaType, quotaMetricTags: Map[String, String]): MetricName = {
    metrics.metricName("broker-value-rate", quotaType.toString,
      "Tracking value-rate per broker", quotaMetricTags.asJava)
  }

  protected def throttleMetricName(quotaType: QuotaType, quotaMetricTags: Map[String, String]): MetricName = {
    metrics.metricName("broker-throttle-time",
      quotaType.toString,
      "Tracking average throttle-time per broker",
      quotaMetricTags.asJava)
  }

  private def getOrCreateQuotaSensors(quotaType: QuotaType): ClientSensors = {
    val sensors = ClientSensors(
      metricsTags,
      getOrCreateSensor(getQuotaSensorName(quotaType, metricsTags), ClientQuotaManager.InactiveSensorExpirationTimeSeconds,
        sensor => sensor.add(clientQuotaMetricName(quotaType, metricsTags), new Rate, getQuotaMetricConfig(quotaLimit(quotaType)))),
      getOrCreateSensor(getThrottleTimeSensorName(quotaType, metricsTags), ClientQuotaManager.InactiveSensorExpirationTimeSeconds,
        sensor => sensor.add(throttleMetricName(quotaType, metricsTags), new Avg))
    )
    sensors
  }

  override def maybeRecordAndGetThrottleTimeMs(request: RequestChannel.Request, value: Double,
    timeMs: Long): Int = throw new UnsupportedOperationException("This method is not supported in BrokerQuotaManager")

  override def maybeRecordAndGetThrottleTimeMs(session: Session, clientId: String, value: Double,
    timeMs: Long): Int = throw new UnsupportedOperationException("This method is not supported in BrokerQuotaManager")

  override def unrecordQuotaSensor(request: RequestChannel.Request, value: Double,
    timeMs: Long): Unit = throw new UnsupportedOperationException("This method is not supported in BrokerQuotaManager")

  override def throttle(request: RequestChannel.Request, throttleCallback: ThrottleCallback,
    throttleTimeMs: Int): Unit = throw new UnsupportedOperationException("This method is not supported in BrokerQuotaManager")

  override def getOrCreateQuotaSensors(session: Session,
    clientId: String): ClientSensors = throw new UnsupportedOperationException("This method is not supported in BrokerQuotaManager")

  override def recordNoThrottle(session: Session, clientId: String,
    value: Double): Unit = throw new UnsupportedOperationException("This method is not supported in BrokerQuotaManager")

  override def getMaxValueInQuotaWindow(session: Session,
    clientId: String): Double = throw new UnsupportedOperationException("This method is not supported in BrokerQuotaManager")

  override def updateQuotaMetricConfigs(
    updatedQuotaEntity: Option[ClientQuotaManager.KafkaQuotaEntity]): Unit = throw new UnsupportedOperationException("This method is not supported in BrokerQuotaManager")

  override def updateQuota(sanitizedUser: Option[String],
    clientId: Option[String],
    sanitizedClientId: Option[String],
    quota: Option[Quota]): Unit = throw new UnsupportedOperationException("This method is not supported in BrokerQuotaManager")
}
