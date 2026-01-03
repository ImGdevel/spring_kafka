package com.study.messaging;

public interface MessagePublisher {

	void publish(String message, String key);
}
