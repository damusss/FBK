package moe.kabii.command.commands.audio

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.sync.withLock
import moe.kabii.command.Command
import moe.kabii.command.params.DiscordParameters
import moe.kabii.data.mongodb.guilds.FeatureChannel
import moe.kabii.discord.audio.AudioManager
import moe.kabii.discord.audio.QueueData
import moe.kabii.discord.util.Embeds

object TrackSkip : AudioCommandContainer {
    suspend fun skip(origin: DiscordParameters, silent: Boolean = false) = with(origin) {
        channelFeatureVerify(FeatureChannel::musicChannel)
        val audio = AudioManager.getGuildAudio(client, target.id.asLong())
        val track = audio.player.playingTrack
        if(track == null) {
            ereply(Embeds.error(i18n("audio_no_track"))).awaitSingle()
            return@with
        }
        if(config.musicBot.autoFSkip && canFSkip(this, track)) {
            audio.player.stopTrack()
            if(!silent) {
                ireply(Embeds.fbk(i18n("audio_fskip", track.info.title))).awaitSingle()
            }
            return@with
        }
        if(!canVoteSkip(this, track)) {
            ereply(Embeds.error(i18n("audio_skip_vc"))).awaitSingle()
            return@with
        }
        val data = track.userData as QueueData
        val votesNeeded = getSkipsNeeded(this)
        val votes = data.voting.withLock {
            if (data.votes.contains(author.id)) {
                val votes = data.votes.count()
                ereply(Embeds.error(i18n("audio_skip_dupe", "track" to track.info.title, "votes" to votes, "votesNeeded" to votesNeeded))).awaitSingle()
                return@with
            }
            data.votes.add(author.id)
            data.votes.count()
        }
        if(votes >= votesNeeded) {
            ireply(Embeds.fbk(i18n("audio_skipped", track.info.title))).awaitSingle()
            audio.player.stopTrack()
        } else {
            ireply(Embeds.fbk(i18n("audio_vote_skip", "track" to track.info.title, "votes" to votes, "needed" to votesNeeded))).awaitSingle()
        }
    }

    object SkipCommand : Command("skip") {
        override val wikiPath: String? = null

        init {
            chat {
                skip(this)
            }

            extern {
                val audio = AudioManager.getGuildAudio(fbk, channel.guildId.asLong())
                val track = audio.player.playingTrack
                if(track == null) {
                    channel.createMessage(Embeds.error(i18n("audio_no_track"))).awaitSingle()
                    return@extern
                }
                audio.player.stopTrack()
                channel.createMessage(Embeds.fbk(i18n("audio_fskip", track.info.title))).awaitSingle()
            }
        }
    }
}