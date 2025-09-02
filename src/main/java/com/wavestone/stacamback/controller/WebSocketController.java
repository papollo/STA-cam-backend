package com.wavestone.stacamback.controller;

import com.wavestone.stacamback.service.YoloProcessingService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.time.LocalDateTime;

@Controller
@RequiredArgsConstructor
public class WebSocketController {

    private final SimpMessagingTemplate messagingTemplate;
    private final YoloProcessingService yoloProcessingService;

    @MessageMapping("/subscribe")
    @SendTo("/topic/detections")
    public void subscribeToDetections() {
        // Send recent detections to new subscribers
        var recentDetections = yoloProcessingService.getRecentDetections();
        messagingTemplate.convertAndSend("/topic/detections/initial", recentDetections);
    }

    @MessageMapping("/ping")
    @SendTo("/topic/ping")
    public String ping() {
        return "pong - " + LocalDateTime.now();
    }
}
