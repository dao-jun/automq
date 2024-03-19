/*
 * Copyright 2024, AutoMQ CO.,LTD.
 *
 * Use of this software is governed by the Business Source License
 * included in the file BSL.md
 *
 * As of the Change Date specified in that file, in accordance with
 * the Business Source License, use of this software will be governed
 * by the Apache License, Version 2.0
 */

package org.apache.kafka.server.metrics.s3stream;

import io.opentelemetry.api.common.AttributeKey;

public class S3StreamKafkaMetricsConstants {
    public static final String AUTO_BALANCER_METRICS_TIME_DELAY_METRIC_NAME = "autobalancer.metrics.time.delay";
    public static final String S3_OBJECT_COUNT_BY_STATE = "s3.object.count";
    public static final String S3_OBJECT_SIZE = "s3.object.size";
    public static final String STREAM_SET_OBJECT_NUM = "stream.set.object.num";
    public static final String STREAM_OBJECT_NUM = "stream.object.num";
    public static final String FETCH_LIMITER_PERMIT_NUM = "fetch.limiter.permit.num";
    public static final String FETCH_PENDING_TASK_NUM = "fetch.pending.task.num";

    public static final AttributeKey<String> LABEL_NODE_ID = AttributeKey.stringKey("node_id");

    public static final AttributeKey<String> LABEL_OBJECT_STATE = AttributeKey.stringKey("state");
    public static final String S3_OBJECT_PREPARED_STATE = "prepared";
    public static final String S3_OBJECT_COMMITTED_STATE = "committed";
    public static final String S3_OBJECT_MARK_DESTROYED_STATE = "mark_destroyed";

    public static final AttributeKey<String> LABEL_FETCH_LIMITER_NAME = AttributeKey.stringKey("limiter_name");
    public static final String FETCH_LIMITER_FAST_NAME = "fast";
    public static final String FETCH_LIMITER_SLOW_NAME = "slow";

    public static final AttributeKey<String> LABEL_FETCH_EXECUTOR_NAME = AttributeKey.stringKey("executor_name");
    public static final String FETCH_EXECUTOR_FAST_NAME = "fast";
    public static final String FETCH_EXECUTOR_SLOW_NAME = "slow";
    public static final String FETCH_EXECUTOR_DELAYED_NAME = "delayed";
}
