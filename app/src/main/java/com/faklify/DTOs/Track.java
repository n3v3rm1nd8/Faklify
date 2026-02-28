package com.faklify.DTOs;

import com.faklify.attributesGenerics.Attributes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Track {

        public String title;
        public Path filePath;
        public long fileSize;
        public TrackStatus status = TrackStatus.NOT_DOWNLOADED;
        public int downloadAttempts = 0;
        public long lastDownloadAttempt = 0;

        public boolean isFileValid() {
            if (status != TrackStatus.DOWNLOADED || filePath == null) {
                return false;
            }
            try {
                return Files.exists(filePath) && Files.size(filePath) > Attributes.MIN_FILE_SIZE;
            } catch (IOException e) {
                return false;
            }
        }
    }
