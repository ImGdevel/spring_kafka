package com.study.notification.provider.sandbox;

import com.study.notification.domain.NotificationChannel;
import com.study.notification.domain.NotificationContent;
import com.study.notification.domain.NotificationSendResult;
import com.study.notification.domain.NotificationSender;
import com.study.notification.domain.NotificationTarget;
import com.study.notification.domain.NotificationTemplate;

/**
 * Email 채널의 샌드박스 전송기 스텁이다.
 */
public class SandboxEmailSender implements NotificationSender {

	@Override
	public NotificationSendResult send(
		NotificationTarget target,
		NotificationContent content,
		NotificationTemplate template
	) {
		return new NotificationSendResult(channel(), true, "EMAIL 샌드박스 스텁");
	}

	@Override
	public NotificationChannel channel() {
		return NotificationChannel.EMAIL;
	}
}
