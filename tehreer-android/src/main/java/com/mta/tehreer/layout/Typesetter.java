/*
 * Copyright (C) 2017 Muhammad Tayyab Akram
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mta.tehreer.layout;

import android.graphics.RectF;
import android.text.SpannableString;
import android.text.Spanned;

import com.mta.tehreer.graphics.Typeface;
import com.mta.tehreer.internal.text.StringUtils;
import com.mta.tehreer.internal.text.TopSpanIterator;
import com.mta.tehreer.layout.style.TypeSizeSpan;
import com.mta.tehreer.layout.style.TypefaceSpan;
import com.mta.tehreer.sfnt.SfntTag;
import com.mta.tehreer.sfnt.ShapingEngine;
import com.mta.tehreer.sfnt.ShapingResult;
import com.mta.tehreer.sfnt.WritingDirection;
import com.mta.tehreer.unicode.BaseDirection;
import com.mta.tehreer.unicode.BidiAlgorithm;
import com.mta.tehreer.unicode.BidiLine;
import com.mta.tehreer.unicode.BidiParagraph;
import com.mta.tehreer.unicode.BidiRun;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Represents a typesetter which performs text layout. It can be used to create lines, perform line
 * breaking, and do other contextual analysis based on the characters in the string.
 */
public class Typesetter {

    private static final float DEFAULT_FONT_SIZE = 16.0f;

    private class Finalizable {

        @Override
        protected void finalize() throws Throwable {
            try {
                dispose();
            } finally {
                super.finalize();
            }
        }
    }

    private static final byte BREAK_TYPE_NONE = 0;
    private static final byte BREAK_TYPE_LINE = 1 << 0;
    private static final byte BREAK_TYPE_CHARACTER = 1 << 2;
    private static final byte BREAK_TYPE_PARAGRAPH = 1 << 4;

    private static byte specializeBreakType(byte breakType, boolean forward) {
        return (byte) (forward ? breakType : breakType << 1);
    }

    private final Finalizable finalizable = new Finalizable();
    private String mText;
    private Spanned mSpanned;
    private byte[] mBreakRecord;
    private ArrayList<BidiParagraph> mBidiParagraphs;
    private ArrayList<IntrinsicRun> mIntrinsicRuns;

    /**
     * Constructs the typesetter object using given text, typeface and type size.
     *
     * @param text The text to typeset.
     * @param typeface The typeface to use.
     * @param typeSize The type size to apply.
     *
     * @throws NullPointerException if <code>text</code> is null, or <code>typeface</code> is null.
     * @throws IllegalArgumentException if <code>text</code> is empty.
     */
	public Typesetter(String text, Typeface typeface, float typeSize) {
        if (text == null) {
            throw new NullPointerException("Text is null");
        }
        if (typeface == null) {
            throw new NullPointerException("Typeface is null");
        }
        if (text.length() == 0) {
            throw new IllegalArgumentException("Text is empty");
        }

        SpannableString spanned = new SpannableString(text);
        spanned.setSpan(new TypefaceSpan(typeface), 0, text.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        spanned.setSpan(new TypeSizeSpan(typeSize), 0, text.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);

        init(text, spanned);
	}

    /**
     * Constructs the typesetter object using a spanned text.
     *
     * @param spanned The spanned text to typeset.
     *
     * @throws NullPointerException if <code>spanned</code> is null.
     * @throws IllegalArgumentException if <code>spanned</code> is empty.
     */
    public Typesetter(Spanned spanned) {
        if (spanned == null) {
            throw new NullPointerException("Spanned text is null");
        }
        if (spanned.length() == 0) {
            throw new IllegalArgumentException("Spanned text is empty");
        }

        init(StringUtils.copyString(spanned), spanned);
    }

    private void init(String text, Spanned spanned) {
        mText = text;
        mSpanned = spanned;
        mBreakRecord = new byte[text.length()];
        mBidiParagraphs = new ArrayList<>();
        mIntrinsicRuns = new ArrayList<>();

        resolveBreaks();
        resolveBidi();
    }

    /**
     * Returns the spanned source text for which this typesetter object was created.
     *
     * @return The spanned source text for which this typesetter object was created.
     */
    public Spanned getSpanned() {
        return mSpanned;
    }

    private void resolveBreaks() {
        resolveBreaks(BreakIterator.getLineInstance(), BREAK_TYPE_LINE);
        resolveBreaks(BreakIterator.getCharacterInstance(), BREAK_TYPE_CHARACTER);
    }

    private void resolveBreaks(BreakIterator breakIterator, byte breakType) {
        breakIterator.setText(mText);
        breakIterator.first();

        byte forwardType = specializeBreakType(breakType, true);
        int charNext;

        while ((charNext = breakIterator.next()) != BreakIterator.DONE) {
            mBreakRecord[charNext - 1] |= forwardType;
        }

        breakIterator.last();
        byte backwardType = specializeBreakType(breakType, false);
        int charIndex;

        while ((charIndex = breakIterator.previous()) != BreakIterator.DONE) {
            mBreakRecord[charIndex] |= backwardType;
        }
    }

    private void resolveBidi() {
        // TODO: Analyze script runs.

        BidiAlgorithm bidiAlgorithm = null;
        ShapingEngine shapingEngine = null;

        try {
            bidiAlgorithm = new BidiAlgorithm(mText);
            shapingEngine = new ShapingEngine();

            BaseDirection baseDirection = BaseDirection.DEFAULT_LEFT_TO_RIGHT;
            byte forwardType = specializeBreakType(BREAK_TYPE_PARAGRAPH, true);
            byte backwardType = specializeBreakType(BREAK_TYPE_PARAGRAPH, false);

            int paragraphStart = 0;
            int suggestedEnd = mText.length();

            while (paragraphStart != suggestedEnd) {
                BidiParagraph paragraph = bidiAlgorithm.createParagraph(paragraphStart, suggestedEnd, baseDirection);
                for (BidiRun bidiRun : paragraph.getLogicalRuns()) {
                    int scriptTag = SfntTag.make(bidiRun.isRightToLeft() ? "arab" : "latn");
                    WritingDirection writingDirection = ShapingEngine.getScriptDirection(scriptTag);

                    shapingEngine.setScriptTag(scriptTag);
                    shapingEngine.setWritingDirection(writingDirection);

                    resolveTypefaces(bidiRun.charStart, bidiRun.charEnd,
                                     bidiRun.embeddingLevel, shapingEngine);
                }
                mBidiParagraphs.add(paragraph);

                mBreakRecord[paragraph.getCharStart()] |= backwardType;
                mBreakRecord[paragraph.getCharEnd() - 1] |= forwardType;

                paragraphStart = paragraph.getCharEnd();
            }
        } finally {
            if (shapingEngine != null) {
                shapingEngine.dispose();
            }
            if (bidiAlgorithm != null) {
                bidiAlgorithm.dispose();
            }
        }
    }

    private void resolveTypefaces(int charStart, int charEnd, byte bidiLevel,
                                  ShapingEngine shapingEngine) {
        Spanned spanned = mSpanned;
        TopSpanIterator<TypefaceSpan> iterator = new TopSpanIterator<>(spanned, charStart, charEnd, TypefaceSpan.class);

        while (iterator.hasNext()) {
            TypefaceSpan spanObject = iterator.next();
            int spanStart = iterator.getSpanStart();
            int spanEnd = iterator.getSpanEnd();

            if (spanObject == null || spanObject.getTypeface() == null) {
                throw new IllegalArgumentException("No typeface is specified for range ["
                                                   + spanStart + ".." + spanEnd + ")");
            }

            resolveFonts(spanStart, spanEnd, bidiLevel, shapingEngine, spanObject.getTypeface());
        }
    }

    private void resolveFonts(int charStart, int charEnd, byte bidiLevel,
                              ShapingEngine shapingEngine, Typeface typeface) {
        Spanned spanned = mSpanned;
        TopSpanIterator<TypeSizeSpan> iterator = new TopSpanIterator<>(spanned, charStart, charEnd, TypeSizeSpan.class);

        while (iterator.hasNext()) {
            TypeSizeSpan spanObject = iterator.next();
            int spanStart = iterator.getSpanStart();
            int spanEnd = iterator.getSpanEnd();

            float typeSize;

            if (spanObject == null) {
                typeSize = DEFAULT_FONT_SIZE;
            } else {
                typeSize = spanObject.getSize();
                if (typeSize < 0.0f) {
                    typeSize = 0.0f;
                }
            }

            IntrinsicRun intrinsicRun = resolveGlyphs(spanStart, spanEnd, bidiLevel, shapingEngine, typeface, typeSize);
            mIntrinsicRuns.add(intrinsicRun);
        }
    }

    private IntrinsicRun resolveGlyphs(int charStart, int charEnd, byte bidiLevel,
                                       ShapingEngine shapingEngine, Typeface typeface, float typeSize) {
        shapingEngine.setTypeface(typeface);
        shapingEngine.setTypeSize(typeSize);

        ShapingResult shapingResult = null;
        IntrinsicRun intrinsicRun = null;

        try {
            shapingResult = shapingEngine.shapeText(mText, charStart, charEnd);
            intrinsicRun = new IntrinsicRun(shapingResult, typeface, typeSize, bidiLevel, shapingEngine.getWritingDirection());
        } finally {
            if (shapingResult != null) {
                shapingResult.dispose();
            }
        }

        return intrinsicRun;
    }

    private String checkRange(int charStart, int charEnd) {
        if (charStart < 0) {
            return ("Char Start: " + charStart);
        }
        if (charEnd > mText.length()) {
            return ("Char End: " + charEnd + ", Text Length: " + mText.length());
        }
        if (charStart >= charEnd) {
            return ("Bad Range: [" + charStart + ".." + charEnd + ")");
        }

        return null;
    }

    private int indexOfBidiParagraph(final int charIndex) {
        return Collections.binarySearch(mBidiParagraphs, null, new Comparator<BidiParagraph>() {
            @Override
            public int compare(BidiParagraph obj1, BidiParagraph obj2) {
                if (charIndex < obj1.getCharStart()) {
                    return 1;
                }

                if (charIndex >= obj1.getCharEnd()) {
                    return -1;
                }

                return 0;
            }
        });
    }

    private int indexOfGlyphRun(final int charIndex) {
        return Collections.binarySearch(mIntrinsicRuns, null, new Comparator<IntrinsicRun>() {
            @Override
            public int compare(IntrinsicRun obj1, IntrinsicRun obj2) {
                if (charIndex < obj1.charStart) {
                    return 1;
                }

                if (charIndex >= obj1.charEnd) {
                    return -1;
                }

                return 0;
            }
        });
    }

    private byte getCharParagraphLevel(int charIndex) {
        int paragraphIndex = indexOfBidiParagraph(charIndex);
        BidiParagraph charParagraph = mBidiParagraphs.get(paragraphIndex);
        return charParagraph.getBaseLevel();
    }

    private float measureChars(int charStart, int charEnd) {
        float measuredWidth = 0.0f;

        if (charEnd > charStart) {
            int runIndex = indexOfGlyphRun(charStart);

            do {
                IntrinsicRun intrinsicRun = mIntrinsicRuns.get(runIndex);
                int glyphStart = intrinsicRun.charGlyphStart(charStart);
                int glyphEnd;

                int segmentEnd = Math.min(charEnd, intrinsicRun.charEnd);
                glyphEnd = intrinsicRun.charGlyphEnd(segmentEnd - 1);

                measuredWidth += intrinsicRun.measureGlyphs(glyphStart, glyphEnd);

                charStart = segmentEnd;
                runIndex++;
            } while (charStart < charEnd);
        }

        return measuredWidth;
    }

    private int findForwardBreak(byte breakType, int charStart, int charEnd, float maxWidth) {
        int forwardBreak = charStart;
        int charIndex = charStart;
        float measuredWidth = 0.0f;

        byte mustType = specializeBreakType(BREAK_TYPE_PARAGRAPH, true);
        breakType = specializeBreakType(breakType, true);

        while (charIndex < charEnd) {
            byte charType = mBreakRecord[charIndex];

            // Handle necessary break.
            if ((charType & mustType) == mustType) {
                int segmentEnd = charIndex + 1;

                measuredWidth += measureChars(forwardBreak, segmentEnd);
                if (measuredWidth <= maxWidth) {
                    forwardBreak = segmentEnd;
                }
                break;
            }

            // Handle optional break.
            if ((charType & breakType) == breakType) {
                int segmentEnd = charIndex + 1;

                measuredWidth += measureChars(forwardBreak, segmentEnd);
                if (measuredWidth > maxWidth) {
                    int whitespaceStart = StringUtils.getTrailingWhitespaceStart(mText, forwardBreak, segmentEnd);
                    float whitespaceWidth = measureChars(whitespaceStart, segmentEnd);

                    // Break if excluding whitespaces width helps.
                    if ((measuredWidth - whitespaceWidth) <= maxWidth) {
                        forwardBreak = segmentEnd;
                    }
                    break;
                }

                forwardBreak = segmentEnd;
            }

            charIndex++;
        }

        return forwardBreak;
    }

    private int findBackwardBreak(byte breakType, int charStart, int charEnd, float maxWidth) {
        int backwardBreak = charEnd;
        int charIndex = charEnd - 1;
        float measuredWidth = 0.0f;

        byte mustType = specializeBreakType(BREAK_TYPE_PARAGRAPH, false);
        breakType = specializeBreakType(breakType, false);

        while (charIndex >= charStart) {
            byte charType = mBreakRecord[charIndex];

            // Handle necessary break.
            if ((charType & mustType) == mustType) {
                measuredWidth += measureChars(backwardBreak, charIndex);
                if (measuredWidth <= maxWidth) {
                    backwardBreak = charIndex;
                }
                break;
            }

            // Handle optional break.
            if ((charType & breakType) == breakType) {
                measuredWidth += measureChars(charIndex, backwardBreak);
                if (measuredWidth > maxWidth) {
                    int whitespaceStart = StringUtils.getTrailingWhitespaceStart(mText, charIndex, backwardBreak);
                    float whitespaceWidth = measureChars(whitespaceStart, backwardBreak);

                    // Break if excluding trailing whitespaces helps.
                    if ((measuredWidth - whitespaceWidth) <= maxWidth) {
                        backwardBreak = charIndex;
                    }
                    break;
                }

                backwardBreak = charIndex;
            }

            charIndex--;
        }

        return backwardBreak;
    }

    private int suggestForwardCharBreak(int charStart, int charEnd, float maxWidth) {
        int forwardBreak = findForwardBreak(BREAK_TYPE_CHARACTER, charStart, charEnd, maxWidth);

        // Take at least one character (grapheme) if max size is too small.
        if (forwardBreak == charStart) {
            for (int i = charStart; i < charEnd; i++) {
                if ((mBreakRecord[i] & BREAK_TYPE_CHARACTER) != 0) {
                    forwardBreak = i + 1;
                    break;
                }
            }

            // Character range does not cover even a single grapheme?
            if (forwardBreak == charStart) {
                forwardBreak = Math.min(charStart + 1, charEnd);
            }
        }

        return forwardBreak;
    }

    private int suggestBackwardCharBreak(int charStart, int charEnd, float maxWidth) {
        int backwardBreak = findBackwardBreak(BREAK_TYPE_CHARACTER, charStart, charEnd, maxWidth);

        // Take at least one character (grapheme) if max size is too small.
        if (backwardBreak == charEnd) {
            for (int i = charEnd - 1; i >= charStart; i++) {
                if ((mBreakRecord[i] & BREAK_TYPE_CHARACTER) != 0) {
                    backwardBreak = i;
                    break;
                }
            }

            // Character range does not cover even a single grapheme?
            if (backwardBreak == charEnd) {
                backwardBreak = Math.max(charEnd - 1, charStart);
            }
        }

        return backwardBreak;
    }

    private int suggestForwardLineBreak(int charStart, int charEnd, float maxWidth) {
        int forwardBreak = findForwardBreak(BREAK_TYPE_LINE, charStart, charEnd, maxWidth);

        // Fallback to character break if no line break occurs in max size.
        if (forwardBreak == charStart) {
            forwardBreak = suggestForwardCharBreak(charStart, charEnd, maxWidth);
        }

        return forwardBreak;
    }

    private int suggestBackwardLineBreak(int charStart, int charEnd, float maxWidth) {
        int backwardBreak = findBackwardBreak(BREAK_TYPE_LINE, charStart, charEnd, maxWidth);

        // Fallback to character break if no line break occurs in max size.
        if (backwardBreak == charEnd) {
            backwardBreak = suggestBackwardCharBreak(charStart, charEnd, maxWidth);
        }

        return backwardBreak;
    }

    /**
     * Suggests a forward break index based on the provided range and width. The measurement
     * proceeds from first character to last character. If there is still room after measuring all
     * characters, then last index is returned. Otherwise, break index is returned.
     *
     * @param charStart The index to the first character (inclusive) for break calculations.
     * @param charEnd The index to the last character (exclusive) for break calculations.
     * @param breakWidth The requested break width.
     * @param breakMode The requested break mode.
     * @return The index (exclusive) that would cause the break.
     *
     * @throws NullPointerException if <code>breakMode</code> is null.
     * @throws IllegalArgumentException if <code>charStart</code> is negative, or
     *         <code>charEnd</code> is greater than the length of source text, or
     *         <code>charStart</code> is greater than or equal to <code>charEnd</code>
     */
    public int suggestForwardBreak(int charStart, int charEnd, float breakWidth, BreakMode breakMode) {
        if (breakMode == null) {
            throw new NullPointerException("Break mode is null");
        }
        String rangeError = checkRange(charStart, charEnd);
        if (rangeError != null) {
            throw new IllegalArgumentException(rangeError);
        }

        switch (breakMode) {
        case CHARACTER:
            return suggestForwardCharBreak(charStart, charEnd, breakWidth);

        case LINE:
            return suggestForwardLineBreak(charStart, charEnd, breakWidth);
        }

        return -1;
    }

    /**
     * Suggests a backward break index based on the provided range and width. The measurement
     * proceeds from last character to first character. If there is still room after measuring all
     * characters, then first index is returned. Otherwise, break index is returned.
     *
     * @param charStart The index to the first character (inclusive) for break calculations.
     * @param charEnd The index to the last character (exclusive) for break calculations.
     * @param breakWidth The requested break width.
     * @param breakMode The requested break mode.
     * @return The index (inclusive) that would cause the break.
     *
     * @throws NullPointerException if <code>breakMode</code> is null.
     * @throws IllegalArgumentException if <code>charStart</code> is negative, or
     *         <code>charEnd</code> is greater than the length of source text, or
     *         <code>charStart</code> is greater than or equal to <code>charEnd</code>
     */
    public int suggestBackwardBreak(int charStart, int charEnd, float breakWidth, BreakMode breakMode) {
        if (breakMode == null) {
            throw new NullPointerException("Break mode is null");
        }
        String rangeError = checkRange(charStart, charEnd);
        if (rangeError != null) {
            throw new IllegalArgumentException(rangeError);
        }

        switch (breakMode) {
        case CHARACTER:
            return suggestBackwardCharBreak(charStart, charEnd, breakWidth);

        case LINE:
            return suggestBackwardLineBreak(charStart, charEnd, breakWidth);
        }

        return -1;
    }

    /**
     * Creates a simple line of specified string range.
     *
     * @param charStart The index to first character of the line in source text.
     * @param charEnd The index after the last character of the line in source text.
     * @return The new line object.
     *
     * @throws IllegalArgumentException if <code>charStart</code> is negative, or
     *         <code>charEnd</code> is greater than the length of source text, or
     *         <code>charStart</code> is greater than or equal to <code>charEnd</code>
     */
	public ComposedLine createSimpleLine(int charStart, int charEnd) {
        String rangeError = checkRange(charStart, charEnd);
        if (rangeError != null) {
            throw new IllegalArgumentException(rangeError);
        }

        ArrayList<GlyphRun> lineRuns = new ArrayList<>();
        addContinuousLineRuns(charStart, charEnd, lineRuns);

		return new ComposedLine(mText, charStart, charEnd, lineRuns, getCharParagraphLevel(charStart));
	}

    private ComposedLine createTruncationToken(int charStart, int charEnd,
                                               TruncationPlace truncationPlace, String tokenStr) {
        int truncationIndex = 0;

        switch (truncationPlace) {
        case START:
            truncationIndex = charStart;
            break;

        case MIDDLE:
            truncationIndex = (charStart + charEnd) / 2;
            break;

        case END:
            truncationIndex = charEnd - 1;
            break;
        }

        Object[] charSpans = mSpanned.getSpans(truncationIndex, truncationIndex + 1, Object.class);
        TypefaceSpan typefaceSpan = null;
        TypeSizeSpan typeSizeSpan = null;

        final int typefaceBit = 1;
        final int typeSizeBit = 1 << 1;
        final int requiredBits = typefaceBit | typeSizeBit;
        int foundBits = 0;

        for (Object span : charSpans) {
            if (span instanceof TypefaceSpan) {
                if (typefaceSpan == null) {
                    typefaceSpan = (TypefaceSpan) span;
                    foundBits |= typefaceBit;
                }
            } else if (span instanceof TypeSizeSpan) {
                if (typeSizeSpan == null) {
                    typeSizeSpan = (TypeSizeSpan) span;
                    foundBits |= typeSizeBit;
                }
            }

            if (foundBits == requiredBits) {
                Typeface tokenTypeface = typefaceSpan.getTypeface();
                float tokenTypeSize = typeSizeSpan.getSize();

                if (tokenStr == null || tokenStr.length() == 0) {
                    // Token string is not given. Use ellipsis character if available; fallback to
                    // three dots.

                    int ellipsisGlyphId = tokenTypeface.getGlyphId(0x2026);
                    if (ellipsisGlyphId == 0) {
                        tokenStr = "...";
                    } else {
                        tokenStr = "\u2026";
                    }
                }

                Typesetter typesetter = new Typesetter(tokenStr, tokenTypeface, tokenTypeSize);
                return typesetter.createSimpleLine(0, tokenStr.length());
            }
        }

        return null;
    }

    /**
     * Creates a line of specified string range, truncating it with ellipsis character (U+2026) or
     * three dots if it overflows the max width.
     *
     * @param charStart The index to first character of the line in source text.
     * @param charEnd The index after the last character of the line in source text.
     * @param maxWidth The width at which truncation will begin.
     * @param breakMode The truncation mode to be used on the line.
     * @param truncationPlace The place of truncation for the line.
     * @return The new line which is truncated if it overflows the <code>maxWidth</code>.
     *
     * @throws NullPointerException if <code>breakMode</code> is null, or
     *         <code>truncationPlace</code> is null.
     * @throws IllegalArgumentException if any of the following is true:
     *         <ul>
     *             <li><code>charStart</code> is negative</li>
     *             <li><code>charEnd</code> is greater than the length of source text</li>
     *             <li><code>charStart</code> is greater than or equal to <code>charEnd</code></li>
     *         </ul>
     */
    public ComposedLine createTruncatedLine(int charStart, int charEnd, float maxWidth,
                                            BreakMode breakMode, TruncationPlace truncationPlace) {
        if (breakMode == null) {
            throw new NullPointerException("Break mode is null");
        }
        if (truncationPlace == null) {
            throw new NullPointerException("Truncation place is null");
        }
        String rangeError = checkRange(charStart, charEnd);
        if (rangeError != null) {
            throw new IllegalArgumentException(rangeError);
        }

        return createCompactLine(charStart, charEnd, maxWidth, breakMode, truncationPlace,
                                 createTruncationToken(charStart, charEnd, truncationPlace, null));
    }

    /**
     * Creates a line of specified string range, truncating it if it overflows the max width.
     *
     * @param charStart The index to first character of the line in source text.
     * @param charEnd The index after the last character of the line in source text.
     * @param maxWidth The width at which truncation will begin.
     * @param breakMode The truncation mode to be used on the line.
     * @param truncationPlace The place of truncation for the line.
     * @param truncationToken The token to indicate the line truncation.
     * @return The new line which is truncated if it overflows the <code>maxWidth</code>.
     *
     * @throws NullPointerException if <code>breakMode</code> is null, or
     *         <code>truncationPlace</code> is null, or <code>truncationToken</code> is null
     * @throws IllegalArgumentException if any of the following is true:
     *         <ul>
     *             <li><code>charStart</code> is negative</li>
     *             <li><code>charEnd</code> is greater than the length of source text</li>
     *             <li><code>charStart</code> is greater than or equal to <code>charEnd</code></li>
     *         </ul>
     */
    public ComposedLine createTruncatedLine(int charStart, int charEnd, float maxWidth,
                                            BreakMode breakMode, TruncationPlace truncationPlace,
                                            String truncationToken) {
        if (breakMode == null) {
            throw new NullPointerException("Break mode is null");
        }
        if (truncationPlace == null) {
            throw new NullPointerException("Truncation place is null");
        }
        if (truncationToken == null) {
            throw new NullPointerException("Truncation token is null");
        }
        String rangeError = checkRange(charStart, charEnd);
        if (rangeError != null) {
            throw new IllegalArgumentException(rangeError);
        }
        if (truncationToken.length() == 0) {
            throw new IllegalArgumentException("Truncation token is empty");
        }

        return createCompactLine(charStart, charEnd, maxWidth, breakMode, truncationPlace,
                                 createTruncationToken(charStart, charEnd, truncationPlace, truncationToken));
    }

    /**
     * Creates a line of specified string range, truncating it if it overflows the max width.
     *
     * @param charStart The index to first character of the line in source text.
     * @param charEnd The index after the last character of the line in source text.
     * @param maxWidth The width at which truncation will begin.
     * @param breakMode The truncation mode to be used on the line.
     * @param truncationPlace The place of truncation for the line.
     * @param truncationToken The token to indicate the line truncation.
     * @return The new line which is truncated if it overflows the <code>maxWidth</code>.
     *
     * @throws NullPointerException if <code>breakMode</code> is null, or
     *         <code>truncationPlace</code> is null, or <code>truncationToken</code> is null
     * @throws IllegalArgumentException if any of the following is true:
     *         <ul>
     *             <li><code>charStart</code> is negative</li>
     *             <li><code>charEnd</code> is greater than the length of source text</li>
     *             <li><code>charStart</code> is greater than or equal to <code>charEnd</code></li>
     *         </ul>
     */
    public ComposedLine createTruncatedLine(int charStart, int charEnd, float maxWidth,
                                            BreakMode breakMode, TruncationPlace truncationPlace,
                                            ComposedLine truncationToken) {
        if (breakMode == null) {
            throw new NullPointerException("Break mode is null");
        }
        if (truncationPlace == null) {
            throw new NullPointerException("Truncation place is null");
        }
        if (truncationToken == null) {
            throw new NullPointerException("Truncation token is null");
        }
        String rangeError = checkRange(charStart, charEnd);
        if (rangeError != null) {
            throw new IllegalArgumentException(rangeError);
        }

        return createCompactLine(charStart, charEnd, maxWidth, breakMode, truncationPlace, truncationToken);
    }

    public ComposedLine createCompactLine(int charStart, int charEnd, float maxWidth,
                                          BreakMode breakMode, TruncationPlace truncationPlace,
                                          ComposedLine truncationToken) {
        float tokenlessWidth = maxWidth - truncationToken.getWidth();

        switch (truncationPlace) {
        case START:
            return createStartTruncatedLine(charStart, charEnd, tokenlessWidth,
                                            breakMode, truncationToken);

        case MIDDLE:
            return createMiddleTruncatedLine(charStart, charEnd, tokenlessWidth,
                                             breakMode, truncationToken);

        case END:
            return createEndTruncatedLine(charStart, charEnd, tokenlessWidth,
                                          breakMode, truncationToken);
        }

        return null;
    }

    private interface BidiRunConsumer {
        void accept(BidiRun bidiRun);
    }

    private class TruncationHandler implements BidiRunConsumer {

        final int charStart;
        final int charEnd;
        final int skipStart;
        final int skipEnd;
        final List<GlyphRun> runList;

        int leadingTokenIndex = -1;
        int trailingTokenIndex = -1;

        TruncationHandler(int charStart, int charEnd, int skipStart, int skipEnd, List<GlyphRun> runList) {
            this.charStart = charStart;
            this.charEnd = charEnd;
            this.skipStart = skipStart;
            this.skipEnd = skipEnd;
            this.runList = runList;
        }

        @Override
        public void accept(BidiRun bidiRun) {
            int visualStart = bidiRun.charStart;
            int visualEnd = bidiRun.charEnd;

            if (bidiRun.isRightToLeft()) {
                // Handle second part of characters.
                if (visualEnd >= skipEnd) {
                    addVisualRuns(Math.max(visualStart, skipEnd), visualEnd, runList);

                    if (visualStart < skipEnd) {
                        trailingTokenIndex = runList.size();
                    }
                }

                // Handle first part of characters.
                if (visualStart <= skipStart) {
                    if (visualEnd > skipStart) {
                        leadingTokenIndex = runList.size();
                    }

                    addVisualRuns(visualStart, Math.min(visualEnd, skipStart), runList);
                }
            } else {
                // Handle first part of characters.
                if (visualStart <= skipStart) {
                    addVisualRuns(visualStart, Math.min(visualEnd, skipStart), runList);

                    if (visualEnd > skipStart) {
                        leadingTokenIndex = runList.size();
                    }
                }

                // Handle second part of characters.
                if (visualEnd >= skipEnd) {
                    if (visualStart < skipEnd) {
                        trailingTokenIndex = runList.size();
                    }

                    addVisualRuns(Math.max(visualStart, skipEnd), visualEnd, runList);
                }
            }
        }

        void addAllRuns() {
            addContinuousLineRuns(charStart, charEnd, this);
        }
    }

    private ComposedLine createStartTruncatedLine(int charStart, int charEnd, float tokenlessWidth,
                                                  BreakMode breakMode, ComposedLine truncationToken) {
        int truncatedStart = suggestBackwardBreak(charStart, charEnd, tokenlessWidth, breakMode);
        if (truncatedStart > charStart) {
            ArrayList<GlyphRun> runList = new ArrayList<>();
            int tokenInsertIndex = 0;

            if (truncatedStart < charEnd) {
                TruncationHandler truncationHandler = new TruncationHandler(charStart, charEnd, charStart, truncatedStart, runList);
                truncationHandler.addAllRuns();

                tokenInsertIndex = truncationHandler.trailingTokenIndex;
            }
            addTruncationTokenRuns(truncationToken, runList, tokenInsertIndex);

            return new ComposedLine(mText, truncatedStart, charEnd, runList, getCharParagraphLevel(truncatedStart));
        }

        return createSimpleLine(truncatedStart, charEnd);
    }

    private ComposedLine createMiddleTruncatedLine(int charStart, int charEnd, float tokenlessWidth,
                                                   BreakMode breakMode, ComposedLine truncationToken) {
        float halfWidth = tokenlessWidth / 2.0f;
        int firstMidEnd = suggestForwardBreak(charStart, charEnd, halfWidth, breakMode);
        int secondMidStart = suggestBackwardBreak(charStart, charEnd, halfWidth, breakMode);

        if (firstMidEnd < secondMidStart) {
            // Exclude inner whitespaces as truncation token replaces them.
            firstMidEnd = StringUtils.getTrailingWhitespaceStart(mText, charStart, firstMidEnd);
            secondMidStart = StringUtils.getLeadingWhitespaceEnd(mText, secondMidStart, charEnd);

            ArrayList<GlyphRun> runList = new ArrayList<>();
            int tokenInsertIndex = 0;

            if (charStart < firstMidEnd || secondMidStart < charEnd) {
                TruncationHandler truncationHandler = new TruncationHandler(charStart, charEnd, firstMidEnd, secondMidStart, runList);
                truncationHandler.addAllRuns();

                tokenInsertIndex = truncationHandler.leadingTokenIndex;
            }
            addTruncationTokenRuns(truncationToken, runList, tokenInsertIndex);

            return new ComposedLine(mText, charStart, charEnd, runList, getCharParagraphLevel(charStart));
        }

        return createSimpleLine(charStart, charEnd);
    }

    private ComposedLine createEndTruncatedLine(int charStart, int charEnd, float tokenlessWidth,
                                                BreakMode breakMode, ComposedLine truncationToken) {
        int truncatedEnd = suggestForwardBreak(charStart, charEnd, tokenlessWidth, breakMode);
        if (truncatedEnd < charEnd) {
            // Exclude trailing whitespaces as truncation token replaces them.
            truncatedEnd = StringUtils.getTrailingWhitespaceStart(mText, charStart, truncatedEnd);

            ArrayList<GlyphRun> runList = new ArrayList<>();
            int tokenInsertIndex = 0;

            if (charStart < truncatedEnd) {
                TruncationHandler truncationHandler = new TruncationHandler(charStart, charEnd, truncatedEnd, charEnd, runList);
                truncationHandler.addAllRuns();

                tokenInsertIndex = truncationHandler.leadingTokenIndex;
            }
            addTruncationTokenRuns(truncationToken, runList, tokenInsertIndex);

            return new ComposedLine(mText, charStart, truncatedEnd, runList, getCharParagraphLevel(charStart));
        }

        return createSimpleLine(charStart, truncatedEnd);
    }

    private void addTruncationTokenRuns(ComposedLine truncationToken, ArrayList<GlyphRun> runList, int insertIndex) {
        for (GlyphRun truncationRun : truncationToken.getRuns()) {
            GlyphRun modifiedRun = new GlyphRun(truncationRun);
            runList.add(insertIndex, modifiedRun);

            insertIndex++;
        }
    }

    private void addContinuousLineRuns(int charStart, int charEnd, BidiRunConsumer runConsumer) {
        int paragraphIndex = indexOfBidiParagraph(charStart);
        int feasibleStart;
        int feasibleEnd;

        do {
            BidiParagraph bidiParagraph = mBidiParagraphs.get(paragraphIndex);
            feasibleStart = Math.max(bidiParagraph.getCharStart(), charStart);
            feasibleEnd = Math.min(bidiParagraph.getCharEnd(), charEnd);

            BidiLine bidiLine = bidiParagraph.createLine(feasibleStart, feasibleEnd);
            for (BidiRun bidiRun : bidiLine.getVisualRuns()) {
                runConsumer.accept(bidiRun);
            }
            bidiLine.dispose();

            paragraphIndex++;
        } while (feasibleEnd != charEnd);
    }

    private void addContinuousLineRuns(int charStart, int charEnd, final List<GlyphRun> runList) {
        addContinuousLineRuns(charStart, charEnd, new BidiRunConsumer() {
            @Override
            public void accept(BidiRun bidiRun) {
                int visualStart = bidiRun.charStart;
                int visualEnd = bidiRun.charEnd;

                addVisualRuns(visualStart, visualEnd, runList);
            }
        });
    }

    private void addVisualRuns(int visualStart, int visualEnd, List<GlyphRun> runList) {
        if (visualStart < visualEnd) {
            // ASSUMPTIONS:
            //      - Visual range may fall in one or more glyph runs.
            //      - Consecutive intrinsic runs may have same bidi level.

            int insertIndex = runList.size();
            IntrinsicRun previousRun = null;

            do {
                int runIndex = indexOfGlyphRun(visualStart);

                IntrinsicRun intrinsicRun = mIntrinsicRuns.get(runIndex);
                int feasibleStart = Math.max(intrinsicRun.charStart, visualStart);
                int feasibleEnd = Math.min(intrinsicRun.charEnd, visualEnd);

                GlyphRun glyphRun = new GlyphRun(intrinsicRun, feasibleStart, feasibleEnd);
                if (previousRun != null) {
                    byte bidiLevel = intrinsicRun.bidiLevel;
                    if (bidiLevel != previousRun.bidiLevel || (bidiLevel & 1) == 0) {
                        insertIndex = runList.size();
                    }
                }
                runList.add(insertIndex, glyphRun);

                previousRun = intrinsicRun;
                visualStart = feasibleEnd;
            } while (visualStart != visualEnd);
        }
    }

    /**
     * Creates a frame full of lines in the rectangle provided by the <code>frameRect</code>
     * parameter. The typesetter will continue to fill the frame until it either runs out of text or
     * it finds that text no longer fits.
     *
     * @param charStart The index to first character of the frame in source text.
     * @param charEnd The index after the last character of the frame in source text.
     * @param frameRect The rectangle specifying the frame to fill.
     * @param textAlignment The horizontal text alignment of the lines in frame.
     * @return The new frame object.
     */
    public ComposedFrame createFrame(int charStart, int charEnd, RectF frameRect, TextAlignment textAlignment) {
        if (frameRect == null) {
            throw new NullPointerException("Frame rect is null");
        }
        if (textAlignment == null) {
            throw new NullPointerException("Text alignment is null");
        }
        String rangeError = checkRange(charStart, charEnd);
        if (rangeError != null) {
            throw new IllegalArgumentException(rangeError);
        }
        if (frameRect.isEmpty()) {
            throw new IllegalArgumentException("Frame rect is empty");
        }

        float flushFactor;
        switch (textAlignment) {
        case RIGHT:
            flushFactor = 1.0f;
            break;

        case CENTER:
            flushFactor = 0.5f;
            break;

        default:
            flushFactor = 0.0f;
            break;
        }

        float frameWidth = frameRect.width();
        float frameHeight = frameRect.height();

        ArrayList<ComposedLine> frameLines = new ArrayList<>();
        int lineStart = charStart;
        float lineY = frameRect.top;

        while (lineStart != charEnd) {
            int lineEnd = suggestForwardBreak(lineStart, charEnd, frameWidth, BreakMode.LINE);
            ComposedLine composedLine = createSimpleLine(lineStart, lineEnd);

            float lineX = composedLine.getFlushPenOffset(flushFactor, frameWidth);
            float lineAscent = composedLine.getAscent();
            float lineHeight = lineAscent + composedLine.getDescent();

            if ((lineY + lineHeight) > frameHeight) {
                break;
            }

            composedLine.setOriginX(frameRect.left + lineX);
            composedLine.setOriginY(lineY + lineAscent);

            frameLines.add(composedLine);

            lineStart = lineEnd;
            lineY += lineHeight;
        }

        return new ComposedFrame(charStart, lineStart, frameLines);
    }

    void dispose() {
        for (BidiParagraph paragraph : mBidiParagraphs) {
            paragraph.dispose();
        }
    }
}
