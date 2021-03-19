/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.features.home.room.detail.timeline.factory

import im.vector.app.core.epoxy.TimelineEmptyItem
import im.vector.app.core.epoxy.TimelineEmptyItem_
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.resources.UserPreferencesProvider
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.helper.TimelineEventVisibilityHelper
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import timber.log.Timber
import javax.inject.Inject

class TimelineItemFactory @Inject constructor(private val messageItemFactory: MessageItemFactory,
                                              private val encryptedItemFactory: EncryptedItemFactory,
                                              private val noticeItemFactory: NoticeItemFactory,
                                              private val defaultItemFactory: DefaultItemFactory,
                                              private val encryptionItemFactory: EncryptionItemFactory,
                                              private val roomCreateItemFactory: RoomCreateItemFactory,
                                              private val widgetItemFactory: WidgetItemFactory,
                                              private val verificationConclusionItemFactory: VerificationItemFactory,
                                              private val callItemFactory: CallItemFactory,
                                              private val timelineEventVisibilityHelper: TimelineEventVisibilityHelper) {

    /**
     * Reminder: nextEvent is older and prevEvent is newer.
     */
    fun create(event: TimelineEvent,
               prevEvent: TimelineEvent?,
               nextEvent: TimelineEvent?,
               eventIdToHighlight: String?,
               callback: TimelineEventController.Callback?): VectorEpoxyModel<*> {
        val highlight = event.root.eventId == eventIdToHighlight
        val computedModel = try {
            if (!timelineEventVisibilityHelper.shouldShowEvent(event, eventIdToHighlight)) {
                return buildEmptyItem(event, prevEvent, eventIdToHighlight)
            }
            when (event.root.getClearType()) {
                // Message items
                EventType.STICKER,
                EventType.MESSAGE -> messageItemFactory.create(event, prevEvent, nextEvent, highlight, callback)
                // Notice items
                EventType.STATE_ROOM_TOMBSTONE,
                EventType.STATE_ROOM_NAME,
                EventType.STATE_ROOM_TOPIC,
                EventType.STATE_ROOM_AVATAR,
                EventType.STATE_ROOM_MEMBER,
                EventType.STATE_ROOM_THIRD_PARTY_INVITE,
                EventType.STATE_ROOM_CANONICAL_ALIAS,
                EventType.STATE_ROOM_JOIN_RULES,
                EventType.STATE_ROOM_HISTORY_VISIBILITY,
                EventType.STATE_ROOM_SERVER_ACL,
                EventType.STATE_ROOM_GUEST_ACCESS,
                EventType.STATE_ROOM_ALIASES,
                EventType.KEY_VERIFICATION_ACCEPT,
                EventType.KEY_VERIFICATION_START,
                EventType.KEY_VERIFICATION_KEY,
                EventType.KEY_VERIFICATION_READY,
                EventType.KEY_VERIFICATION_MAC,
                EventType.CALL_CANDIDATES,
                EventType.CALL_REPLACES,
                EventType.CALL_SELECT_ANSWER,
                EventType.CALL_NEGOTIATE,
                EventType.REACTION,
                EventType.STATE_ROOM_POWER_LEVELS -> noticeItemFactory.create(event, highlight, callback)
                EventType.STATE_ROOM_WIDGET_LEGACY,
                EventType.STATE_ROOM_WIDGET -> widgetItemFactory.create(event, highlight, callback)
                EventType.STATE_ROOM_ENCRYPTION -> encryptionItemFactory.create(event, highlight, callback)
                // State room create
                EventType.STATE_ROOM_CREATE -> roomCreateItemFactory.create(event, callback)
                // Calls
                EventType.CALL_INVITE,
                EventType.CALL_HANGUP,
                EventType.CALL_REJECT,
                EventType.CALL_ANSWER -> callItemFactory.create(event, highlight, callback)
                // Crypto
                EventType.ENCRYPTED -> {
                    if (event.root.isRedacted()) {
                        // Redacted event, let the MessageItemFactory handle it
                        messageItemFactory.create(event, prevEvent, nextEvent, highlight, callback)
                    } else {
                        encryptedItemFactory.create(event, prevEvent, nextEvent, highlight, callback)
                    }
                }
                EventType.KEY_VERIFICATION_CANCEL,
                EventType.KEY_VERIFICATION_DONE -> {
                    verificationConclusionItemFactory.create(event, highlight, callback)
                }
                // Unhandled event types
                else                              -> {
                    Timber.v("Type ${event.root.getClearType()} not handled")
                    defaultItemFactory.create(event, highlight, callback)
                }
            }
        } catch (throwable: Throwable) {
            Timber.e(throwable, "failed to create message item")
            defaultItemFactory.create(event, highlight, callback, throwable)
        }
        return computedModel ?: buildEmptyItem(event, prevEvent, eventIdToHighlight)
    }

    private fun buildEmptyItem(timelineEvent: TimelineEvent, prevEvent: TimelineEvent?, eventIdToHighlight: String?): TimelineEmptyItem {
        val makesEmptyItemVisible = prevEvent == null || timelineEventVisibilityHelper.shouldShowEvent(prevEvent, eventIdToHighlight)
        return TimelineEmptyItem_()
                .id(timelineEvent.localId)
                .eventId(timelineEvent.eventId)
                .visible(makesEmptyItemVisible)
    }
}
