package moe.kabii.discord.tasks

import discord4j.common.util.TimestampFormat
import discord4j.core.`object`.entity.channel.GuildMessageChannel
import discord4j.core.`object`.entity.channel.MessageChannel
import discord4j.core.`object`.entity.channel.PrivateChannel
import discord4j.core.spec.EmbedCreateFields
import discord4j.rest.http.client.ClientException
import kotlinx.coroutines.*
import kotlinx.coroutines.reactive.awaitSingle
import moe.kabii.LOG
import moe.kabii.data.relational.discord.Reminder
import moe.kabii.data.relational.discord.Reminders
import moe.kabii.discord.util.Embeds
import moe.kabii.discord.util.MessageColors
import moe.kabii.instances.DiscordInstances
import moe.kabii.trackers.ServiceRequestCooldownSpec
import moe.kabii.util.constants.EmojiCharacters
import moe.kabii.util.extensions.*
import org.joda.time.DateTime
import java.time.Duration
import java.time.Instant
import kotlin.math.max

class ReminderWatcher(val instances: DiscordInstances, cooldown: ServiceRequestCooldownSpec) : Runnable {
    private val updateInterval = cooldown.minimumRepeatTime

    override fun run() {
        applicationLoop {
            // grab reminders ending in next 2 minutes
            val start = Instant.now()
            propagateTransaction {
                try {
                    val window = DateTime.now().plus(updateInterval)
                    val reminders = Reminder.find { Reminders.remind lessEq window }.toList()

                    // launch coroutine for precise reminder notifications. run on reminder dispatcher threads
                    val job = SupervisorJob()
                    val discordScope = CoroutineScope(DiscordTaskPool.reminderThreads + job)

                    reminders.map { reminder ->
                        discordScope.launch {
                            propagateTransaction {
                                scheduleReminder(reminder)
                            }
                        }
                    }.joinAll() // wait for all reminders to finish to make sure these are removed before next set
                } catch(e: Exception) {
                    LOG.error("Uncaught exception in ReminderWatcher :: ${e.message}")
                    LOG.debug(e.stackTraceString)
                } // don't let this thread die
            }
            val runtime = Duration.between(start, Instant.now())
            val delay = updateInterval - runtime.toMillis()
            delay(max(delay, 0L)) // don't sleep negative - not sure how this was happening though
        }
    }

    @RequiresExposedContext
    private suspend fun scheduleReminder(reminder: Reminder) {
        val discord = instances[reminder.discordClient].client

        val time = Duration.between(Instant.now(), reminder.remind.javaInstant)
        delay(max(time.toMillis(), 0L))
        val user = discord.getUserById(reminder.user.userID.snowflake)
            .tryAwait().orNull()
        if(user == null) {
            LOG.warn("Skipping reminder: user ${reminder.user} not found") // this should not happen
            return
        }

        // try to send reminder, send in PM if failed
        val clock = EmojiCharacters.alarm
        val created = "Reminder created at ${TimestampFormat.SHORT_DATE_TIME.format(reminder.created.javaInstant)}"
        val desc = if(reminder.originMessage != null) {
            "[$created](${reminder.originMessage!!.jumpLink})"
        } else created

        val embed = Embeds.other(desc, MessageColors.reminder)
            .withAuthor(EmbedCreateFields.Author.of("$clock Reminder for ${user.userAddress()} $clock", null, user.avatarUrl))
            .run {
                val content = reminder.content
                if(content != null) withFields(EmbedCreateFields.Field.of("Reminder: ", content, false)) else this
            }

        suspend fun sendReminder(target: MessageChannel, reason: String? = null) {
            val edited = if(reason != null) embed.withFooter(EmbedCreateFields.Footer.of("Reminder sent in DM: $reason.", null)) else embed
            target.createMessage(user.mention)
                .withEmbeds(edited)
                .awaitSingle()
        }

        suspend fun dmFallback(reason: String) {
            val dmChannel = user.privateChannel.tryAwait().orNull()
            if(dmChannel != null) {
                sendReminder(dmChannel, reason)
            } else {
                LOG.info("Unable to send reminder: unable to send DM fallback message :: $reminder")
            }
        }
        // if guild channel, try to send and fall back to DM
        // if DM channel, send to DM
        val discordChannel = discord.getChannelById(reminder.channel.snowflake)
            .ofType(MessageChannel::class.java)
            .tryAwait().orNull()
        try {
            when (discordChannel) {
                is PrivateChannel -> sendReminder(discordChannel)
                is GuildMessageChannel -> {
                    val member = user.asMember(discordChannel.guildId).tryAwait().orNull()
                    if (member != null) {
                        try {
                            sendReminder(discordChannel)
                        } catch (ce: ClientException) {
                            val err = ce.status.code()
                            LOG.info(ce.stackTraceString)
                            if (err == 403 || err == 404) {
                                // unable to send message, try to DM fallback
                                dmFallback("missing message permissions in original channel")
                            }
                        }

                    } else {
                        // member no longer in server, try to DM fallback
                        dmFallback("user not in original server")
                    }
                }
            }
        } catch(ce: ClientException) {
            LOG.info("Completely unable to send reminder: skipping :: $reminder")
        } catch(e: Exception) {
            LOG.error("Uncaught exception sending reminder :: ${e.message}")
            LOG.debug(e.stackTraceString)
        }
        reminder.delete()
    }

}