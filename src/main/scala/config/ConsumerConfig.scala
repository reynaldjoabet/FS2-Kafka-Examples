package config
import cats.data.{NonEmptyList => Nel}
import fs2.kafka.AutoOffsetReset
import scala.concurrent.duration.FiniteDuration

final case class ConsumerConfig(
    bootstrapServers: Nel[String],
    topics: Nel[String],
    groupId: String,
    autoOffsetReset: AutoOffsetReset,
    autoCommitEnabled: Boolean,
    apiTimeout: FiniteDuration
)

object ConsumerConfig {}
