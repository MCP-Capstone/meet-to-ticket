package com.mcpcapstone.meettoticket.meeting;

import com.mcpcapstone.meettoticket.project.Project;
import com.mcpcapstone.meettoticket.user.User;

import jakarta.persistence.*;

import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "meetings", indexes = @Index(columnList = "project_id,meeting_date"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Meeting {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "meeting_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(name = "meeting_date", nullable = false)
    private LocalDate meetingDate;

    @Column(name = "participants_json", nullable = false, columnDefinition = "text")
    private String participantsJson;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "original_content", nullable = false, columnDefinition = "text")
    private String originalContent;

    public Meeting(
            Project project,
            User user,
            String title,
            LocalDate date,
            String participants,
            String filename,
            String content) {
        this.project = project;
        this.createdBy = user;
        this.title = title;
        this.meetingDate = date;
        this.participantsJson = participants;
        this.originalFilename = filename;
        this.originalContent = content;
    }
}
