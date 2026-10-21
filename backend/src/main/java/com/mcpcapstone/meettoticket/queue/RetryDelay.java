package com.mcpcapstone.meettoticket.queue;

@FunctionalInterface
public interface RetryDelay {
	void sleep(long millis) throws InterruptedException;
}
