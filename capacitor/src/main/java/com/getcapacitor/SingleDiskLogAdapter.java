package com.getcapacitor;
import android.content.Context;
import android.os.*;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.orhanobut.logger.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.Exception;
import java.text.SimpleDateFormat;
import java.util.*;



public class SingleDiskLogAdapter implements LogAdapter {
  @NonNull private final Handler handler;

  public SingleDiskLogAdapter(@NonNull Handler handler) {
    this.handler = checkNotNull(handler);
  }

  @Override
  public boolean isLoggable(int priority, @Nullable String tag) {
    return false;
  }

  @Override public void log(int level, @Nullable String tag, @NonNull String message) {
    checkNotNull(message);

    // do nothing on the calling thread, simply pass the tag/msg to the background thread
    handler.sendMessage(handler.obtainMessage(level, message));
  }

  @NonNull static <T> T checkNotNull(@Nullable final T obj) {
    if (obj == null) {
      throw new NullPointerException();
    }
    return obj;
  }
  static class WriteHandler extends Handler {

    @NonNull private final String folder;
    private final int maxFileSize;

    public WriteHandler(@NonNull Looper looper, @NonNull String folder, int maxFileSize) {
      super(checkNotNull(looper));
      this.folder = checkNotNull(folder);
      this.maxFileSize = maxFileSize;
    }

    @SuppressWarnings("checkstyle:emptyblock")
    @Override
    public void handleMessage(@NonNull Message msg) {
      String content = (String) msg.obj;

      FileWriter fileWriter = null;
      File logFile = getLogFile(folder, "logs");

      try {
        fileWriter = new FileWriter(logFile, true);

        writeLog(fileWriter, content);

        fileWriter.flush();
        fileWriter.close();
      } catch (IOException e) {
        if (fileWriter != null) {
          try {
            fileWriter.flush();
            fileWriter.close();
          } catch (IOException e1) { /* fail silently */ }
        }
      }
    }

    /**
     * This is always called on a single background thread.
     * Implementing classes must ONLY write to the fileWriter and nothing more.
     * The abstract class takes care of everything else including close the stream and catching IOException
     *
     * @param fileWriter an instance of FileWriter already initialised to the correct file
     */
    private void writeLog(@NonNull FileWriter fileWriter, @NonNull String content) throws IOException {
      checkNotNull(fileWriter);
      checkNotNull(content);

      fileWriter.append(content);
    }

    private File getLogFile(@NonNull String folderName, @NonNull String fileName) {
      checkNotNull(folderName);
      checkNotNull(fileName);
      File folder = new File(folderName);
      if (!folder.exists()) {
        //TODO: What if folder is not created, what happens then?
        folder.mkdirs();
      }
      int newFileCount = 0;
      File newFile;
      File existingFile = null;

      newFile = new File(folder, String.format("%s_%s.csv", fileName, newFileCount));
      while (newFile.exists()) {
        existingFile = newFile;
        newFileCount++;
        newFile = new File(folder, String.format("%s_%s.csv", fileName, newFileCount));
      }

      if (existingFile != null) {
        if (existingFile.length() >= maxFileSize) {
          return newFile;
        }
        return existingFile;
      }

      return newFile;
    }
    @NonNull static <T> T checkNotNull(@Nullable final T obj) {
      if (obj == null) {
        throw new NullPointerException();
      }
      return obj;
    }

    public static FormatStrategy getFormatStrategy(Context context,String filed){

      String filedirpath = context.getExternalFilesDir(filed).getPath();  //文件夹
      HandlerThread ht = new HandlerThread("AndroidFileLogger." + filedirpath);
      ht.start();
      Handler handler = new WriteHandler(ht.getLooper(), filedirpath, 500 * 1024);
      LogStrategy logStrategy = new DiskLogStrategy(handler);

      FormatStrategy formatStrategy = CsvFormatStrategy.newBuilder()
        .logStrategy(logStrategy)
//        .tag(tag)
        .build();
      return  formatStrategy;
    }

  }
}
