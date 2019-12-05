/*
  AUTH | hwding
  DATE | Mar 08 2019
  DESC | textual watermark remover for PDF files
  MAIL | m@amastigote.com
  GITH | github.com/hwding
 */
package com.amastigote.unstamper.core;

import com.amastigote.unstamper.log.GeneralLogger;
import org.jetbrains.annotations.NotNull;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.text.PDFMarkedContentExtractor;
import org.apache.pdfbox.text.TextPosition;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.util.stream.Collectors;

public class Processor {

    private static final byte[] empBytes = new byte[0];
    private static final Object empObj = new Object();
    private static final Collection<Object> empObjCol = Collections.singleton(empObj);
    private static final List<PDAnnotation> empList = Collections.emptyList();

    private static final String SHOW_TEXT = "Tj";
    private static final String SHOW_TEXT_ADJUSTED = "TJ";
    private static final String BEGIN_MARKED_CONTENT_WITH_PROPERTY = "BDC";
    private static final String END_MARKED_CONTENT = "EMC";

    public static void process(
            @NotNull File file,
            @NotNull String[] strings,
            @NotNull boolean useStrict,
            @NotNull boolean removeAnnotations) {
        GeneralLogger.Processor.procInProgress(file.getName());

        try {
            if (!file.canWrite()) {
                GeneralLogger.File.notWritable(file.getName());
                return;
            }

            PDDocument pdDocument = PDDocument.load(file);

            pdDocument.getPages().forEach(pdPage -> {
                try {
                    boolean needRewrite;

                    /* >> loading font resources from current page */
                    PDFStreamParser pdfStreamParser = new PDFStreamParser(pdPage);
                    pdfStreamParser.parse();

                    Set<PDFont> pdFonts = new HashSet<>();

                    pdPage.getResources().getFontNames().forEach(fontName -> {
                        /* Ignore Any Exception During Parallel Processing */
                        try {
                            PDFont pdFont = pdPage.getResources().getFont(fontName);
                            System.out.println(fontName);
                            if (Objects.nonNull(pdFont))
                                pdFonts.add(pdFont);
                        } catch (Exception ignored) {
                        }
                    });
                    /* << loading font resources from current page */

                    /* >> pre-check marked contents and remember orders */
                    final PDFMarkedContentExtractor extractor = new PDFMarkedContentExtractor();
                    extractor.processPage(pdPage);

                    final List<PDMarkedContent> markedContents = extractor.getMarkedContents();
                    final List<PDMarkedContent> flattenedMarkedContents = new ArrayList<>(markedContents.size());

                    new MarkedContentFlattener()
                            .container(flattenedMarkedContents)
                            .flatten(markedContents);

                    final List<Boolean> markedContentMatchRecords = flattenedMarkedContents
                            .stream()
                            .map(PDMarkedContent::getContents)
                            .map(c -> c.stream()
                                    .filter(e -> e instanceof TextPosition)
                                    .map(e -> ((TextPosition) e).getUnicode())
                                    .collect(Collectors.joining()))
                            .map(s -> TextStampRecognizer.recognizePlain(strings, s.getBytes(), useStrict))
                            .collect(Collectors.toList());

                    needRewrite = markedContentMatchRecords.contains(true);
                    /* << pre-check marked contents and remember orders */

                    int mcCount = -1;
                    boolean mcRemovingFlag = false;

                    /* handle both string array and string */
                    List<Object> objects = pdfStreamParser.getTokens();
                    Object object, prevObject;
                    for (int i = 0; i < objects.size(); i++) {
                        object = objects.get(i);

                        /* >> mark marked content */
                        if (object instanceof Operator) {
                            Operator op = (Operator) object;
                            if (BEGIN_MARKED_CONTENT_WITH_PROPERTY.equals(op.getName())) {
                                ++mcCount;
                                mcRemovingFlag = markedContentMatchRecords.get(mcCount);
                            } else if (END_MARKED_CONTENT.equals(op.getName())) {
                                mcRemovingFlag = false;
                                objects.set(i, empObj);
                            }
                        }

                        if (mcRemovingFlag) {
                            objects.set(i, empObj);
                            continue;
                        }
                        /* << mark marked content */

                        if (object instanceof Operator) {
                            Operator op = (Operator) object;
                            String testStr;
                            boolean isArray = false;

                            if (SHOW_TEXT.equals(op.getName()) || (SHOW_TEXT_ADJUSTED.equals(op.getName()))) {
                                if (i == 0) {
                                    continue;
                                }

                                prevObject = objects.get(i - 1);
                                if (prevObject instanceof COSArray) {
                                    isArray = true;
                                    COSArray array = (COSArray) prevObject;
                                    StringBuilder builder = new StringBuilder();

                                    for (int j = 0; j < array.size(); j++) {
                                        if (Objects.isNull(array.getString(j))) {
                                            continue;
                                        }
                                        builder.append(array.getString(j));
                                    }
                                    testStr = builder.toString();
                                } else if (prevObject instanceof COSString) {
                                    System.out.println(((COSString) prevObject).getForceHexForm());
                                    testStr = ((COSString) prevObject).toHexString();

                                } else {
                                    continue;
                                }

                                try {
                                    if (TextStampRecognizer.recognize(strings, testStr.getBytes(), pdFonts, useStrict)) {
                                        if (isArray) {
                                            ((COSArray) prevObject).clear();
                                        } else {
                                            ((COSString) prevObject).setValue(empBytes);
                                        }

                                        /* only remove annotations of page where we find matched watermark */
                                        if (removeAnnotations) {
                                            pdPage.setAnnotations(empList);
                                        }

                                        needRewrite = true;
                                    }
                                } catch (Exception ignored) {
                                }
                            }
                        }
                    }

                    if (needRewrite) {

                        /* >> write modified tokens back to the stream */
                        PDStream newContents = new PDStream(pdDocument);
                        OutputStream out = newContents.createOutputStream(COSName.FLATE_DECODE);
                        ContentStreamWriter writer = new ContentStreamWriter(out);

                        /* remove all marked marked contents */
                        objects.removeAll(empObjCol);

                        writer.writeTokens(objects);
                        out.close();
                        /* << write modified tokens back to the stream */

                        pdPage.setContents(newContents);
                    }
                } catch (Exception ignored) {
                    GeneralLogger.Processor.errorProcess(file.getName());
                }
            });

            /* write back to the file */
            pdDocument.setAllSecurityToBeRemoved(true);
            pdDocument.save(file);
            pdDocument.close();
        } catch (IOException ignored) {
            GeneralLogger.Processor.errorLoadPdf(file.getName());
        }
    }
}
