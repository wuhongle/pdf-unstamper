/*
  AUTH | hwding
  DATE | Dec 25 2018
  DESC | textual watermark remover for PDF files
  MAIL | m@amastigote.com
  GITH | github.com/hwding
 */
package com.amastigote.unstamper.util;

import com.amastigote.unstamper.core.Processor;
import com.amastigote.unstamper.io.IOHandler;
import com.amastigote.unstamper.log.GeneralLogger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Objects;

public class TaskRunner {
    private static String[] keywords;
    private static boolean useStrict;
    private static boolean removeAnnotations;

    public static void init(
            @NotNull String[] keywords,
            @NotNull boolean useStrict,
            @NotNull boolean removeAnnotations) {
        TaskRunner.keywords = keywords;
        TaskRunner.useStrict = useStrict;
        TaskRunner.removeAnnotations = removeAnnotations;
    }

    public static void procSingleFile(
            @NotNull String ifn,
            @NotNull String ofn) {
        try {
            File file = IOHandler.getCopiedFile(ifn, ofn);
            if (Objects.isNull(file)) {
                GeneralLogger.File.notExist(ifn);
                return;
            }
            submitToProcessor(file);
        } catch (IOException e) {
            GeneralLogger.File.error(ifn);
        } catch (IOHandler.FileNameDuplicateException e) {
            GeneralLogger.File.nameDuplicate(ifn);
        }
    }

    private static void submitToProcessor(@NotNull File file) {
        Processor.process(file, keywords, useStrict, removeAnnotations);
    }

    public static void procSingleFileDirectly(@NotNull String ifn) {
        File file = IOHandler.getFile(ifn);
        if (Objects.isNull(file)) {
            GeneralLogger.File.notExist(ifn);
            return;
        }
        submitToProcessor(file);
    }

    public static void procMassFiles(
            @NotNull String idn,
            @NotNull String odn,
            @NotNull boolean recursively) {
        Iterator<File> fileIterator = null;
        try {
            fileIterator = IOHandler.getCopiedFiles(idn, odn, recursively);
        } catch (IOException e) {
            GeneralLogger.File.error(idn);
        } catch (IOHandler.FileNameDuplicateException e) {
            GeneralLogger.File.nameDuplicate(idn);
        }
        procIterator(fileIterator, idn);
    }

    public static void procMassFilesDirectly(
            @NotNull String idn,
            @NotNull boolean recursively) {
        Iterator<File> fileIterator = IOHandler.getFiles(idn, recursively);
        procIterator(fileIterator, idn);
    }

    private static void procIterator(
            @Nullable Iterator<File> fileIterator,
            @NotNull String idn) {
        if (Objects.isNull(fileIterator)) {
            GeneralLogger.File.notExist(idn);
            return;
        }
        fileIterator.forEachRemaining(TaskRunner::submitToProcessor);
    }
}
