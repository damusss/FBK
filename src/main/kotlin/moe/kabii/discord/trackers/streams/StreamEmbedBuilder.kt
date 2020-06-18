package moe.kabii.discord.trackers.streams

import discord4j.rest.util.Color
import moe.kabii.data.mongodb.FeatureSettings
import moe.kabii.data.relational.TrackedStreams
import moe.kabii.structure.EmbedBlock
import moe.kabii.structure.javaInstant
import moe.kabii.util.DurationFormatter
import java.time.Duration
import java.time.Instant

class StreamEmbedBuilder(val user: StreamUser, val settings: FeatureSettings) {
    fun stream(liveStream: StreamDescriptor) =
        StreamEmbed(liveStream, this)
    fun statistics(dbStream: TrackedStreams.Stream) =
        StatisticsEmbed(dbStream, this)

    class StreamEmbed internal constructor(stream: StreamDescriptor, builder: StreamEmbedBuilder) {
        val title = "${stream.username} playing ${stream.game.name} for ${stream.viewers} viewers"
        val url = stream.user.url
        val description = "[${stream.title}]($url)"

        private val applyBase: EmbedBlock = {
            setAuthor(title, url, stream.user.profileImage)
            setDescription(description)
            setThumbnail(stream.game.artURL)
            setColor(stream.parser.color)
            if(builder.settings.streamThumbnails) {
                setImage(stream.user.thumbnailUrl)
            }
        }

        val automatic: EmbedBlock = {
            applyBase(this)
            val time = Duration.between(stream.startedAt, Instant.now())
            val uptime = DurationFormatter(time).asUptime
            setFooter("Uptime: $uptime - Live since ", stream.parser.icon)
            setTimestamp(stream.startedAt)
        }

        val manual: EmbedBlock = {
            applyBase(this)
            val time = Duration.between(stream.startedAt, Instant.now())
            val uptime = DurationFormatter(time).asUptime
            setFooter("Uptime: $uptime", stream.parser.icon)
        }
    }

    class StatisticsEmbed internal constructor(dbStream: TrackedStreams.Stream, builder: StreamEmbedBuilder) {
        val create: EmbedBlock = {
            val recordedUptime = Duration.between(dbStream.startTime.javaInstant, Instant.now())
            val uptime = DurationFormatter(recordedUptime).fullTime
            val description = StringBuilder()
            if(builder.settings.streamEndTitle && dbStream.lastTitle.isNotBlank()) {
                description.append("Last stream title: ")
                    .append(dbStream.lastTitle)
                    .append('\n')
            }
            if(builder.settings.streamEndGame) {
                description.append("Last game played: ")
                    .append(dbStream.lastGame)
                    .append('\n')
            }
            if(builder.settings.streamPeakViewers) {
                description.append("Peak viewers: ")
                    .append(dbStream.peakViewers)
                    .append('\n')
            }
            if(builder.settings.streamAverageViewers) {
                description.append("Average viewers: ")
                    .append(dbStream.averageViewers)
                    .append('\n')
            }

            setAuthor("${builder.user.displayName} was live for $uptime", builder.user.url, builder.user.profileImage)
            setColor(Color.of(3941986))
            val desc = description.toString()
            if(desc.isNotBlank()) setDescription(desc)
            setFooter("Stream ended ", builder.user.parser.icon)
            setTimestamp(Instant.now())
        }
    }
}