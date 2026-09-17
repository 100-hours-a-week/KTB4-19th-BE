package com.homes.zipsai.conversation.dto.response;

import com.homes.zipsai.building.domain.Complaint;
import com.homes.zipsai.conversation.domain.Conversation;

record ConversationDisplayStatus(String code, String label) {

    static ConversationDisplayStatus of(Conversation conversation, Complaint complaint) {
        if (complaint != null) {
            return new ConversationDisplayStatus(complaint.getStatus().name(), complaint.getStatus().getLabel());
        }
        return new ConversationDisplayStatus(conversation.getStatus().name(), conversation.getStatus().getLabel());
    }
}
