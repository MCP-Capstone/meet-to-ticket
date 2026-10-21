package com.mcpcapstone.meettoticket.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mcpcapstone.meettoticket.entity.Meeting;

public interface MeetingRepository extends JpaRepository<Meeting, Long> {
}
