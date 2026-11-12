package com.safepath.backend.websocket.dto;

import jakarta.validation.constraints.NotBlank;

public record ConnectionMessageRequest(
        @NotBlank(message = "보낸 사람 식별자는 필수입니다.")
        String senderId,

        @NotBlank(message = "메시지 내용은 필수입니다.")
        String content
) {
}
