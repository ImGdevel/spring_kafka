package com.study.rediscompare.web;

import com.study.messaging.MessagePublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/messages")
public class MessageController {

	private final MessagePublisher messagePublisher;

	public MessageController(MessagePublisher messagePublisher) {
		this.messagePublisher = messagePublisher;
	}

	@PostMapping
	public ResponseEntity<String> send(@RequestBody MessageRequest request) {
		messagePublisher.publish(request.message(), request.key());
		return ResponseEntity.accepted().body("sent");
	}
}
