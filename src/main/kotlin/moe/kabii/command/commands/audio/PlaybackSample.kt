package moe.kabii.command.commands.audio

import kotlinx.coroutines.reactive.awaitSingle
import moe.kabii.command.Command
import moe.kabii.discord.audio.AudioManager
import moe.kabii.discord.audio.QueueData
import moe.kabii.discord.util.Embeds
import moe.kabii.rusty.Try
import moe.kabii.util.DurationFormatter
import moe.kabii.util.DurationParser

object PlaybackSample : AudioCommandContainer {
    object SampleTrack : Command("sample") {
        override val wikiPath = "Music-Player#playback-manipulation"

        init {
            discord {
                val audio = AudioManager.getGuildAudio(target.id.asLong())
                val track = audio.player.playingTrack
                if(track == null) {
                    ereply(Embeds.error("There is no track currently playing.")).awaitSingle()
                    return@discord
                }
                if(config.musicBot.restrictSeek && !canFSkip(this, track)) {
                    ereply(Embeds.error("You must be the DJ (track requester) or a channel moderator to limit this track's playback.")).awaitSingle()
                    return@discord
                }
                val sampleArg = args.string("duration")
                val sampleTime = DurationParser
                    .tryParse(sampleArg)
                    ?.run { Try(::toMillis) }
                    ?.result?.orNull()

                if(sampleTime == null) {
                    ereply(Embeds.error("**$sampleArg** is not a valid length of time. Example: **sample 2m**.")).awaitSingle()
                    return@discord
                }
                val remaining = track.duration - track.position
                if(sampleTime > remaining) {
                    val remainingTime = DurationFormatter(remaining).colonTime
                    ereply(Embeds.error("The current track only has $remainingTime remaining to play.")).awaitSingle()
                    return@discord
                }
                val endTarget = sampleTime + track.position
                val data = track.userData as QueueData
                data.endMarkerMillis = endTarget
                val formattedTime = DurationFormatter(sampleTime).colonTime
                val skipTime = DurationFormatter(endTarget).colonTime
                ireply(Embeds.fbk("Sampling $formattedTime of ${trackString(track, includeAuthor = false)} -> skipping track at $skipTime.")).awaitSingle()
            }
        }
    }

    object SampleTrackTimestamp : Command("sampleto") {
        override val wikiPath = "Music-Player#playback-manipulation"

        init {
            discord {
                val audio = AudioManager.getGuildAudio(target.id.asLong())
                val track = audio.player.playingTrack
                if(track == null) {
                    ereply(Embeds.error("There is no track currently playing.")).awaitSingle()
                    return@discord
                }
                if(config.musicBot.restrictSeek && !canFSkip(this, track)) {
                    ereply(Embeds.error("You must be the DJ (track requester) or a channel moderator to limit this track's playback.")).awaitSingle()
                    return@discord
                }

                val timeArg = args.string("timestamp")
                val sampleTo = DurationParser
                    .tryParse(timeArg)
                    ?.run { Try(::toMillis) }
                    ?.result?.orNull()
                    ?.let { position ->
                        if(position > track.duration) track.duration else position
                    }
                if(sampleTo == null) {
                    ereply(Embeds.error("**$timeArg** is not a valid timestamp. Example: **sample 2m**.")).awaitSingle()
                    return@discord
                }
                if(track.position > sampleTo) {
                    val targetColon = DurationFormatter(sampleTo).colonTime
                    ereply(Embeds.error("The current track is already beyond the timestamp **$targetColon**.")).awaitSingle()
                    return@discord
                }
                val data = track.userData as QueueData
                data.endMarkerMillis = sampleTo
                val skipAt = DurationFormatter(sampleTo).colonTime
                ireply(Embeds.fbk("Sampling ${trackString(track, includeAuthor = false)} -> skipping track at $skipAt.")).awaitSingle()
            }
        }
    }
}