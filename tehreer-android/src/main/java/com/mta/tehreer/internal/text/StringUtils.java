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

package com.mta.tehreer.internal.text;

import android.text.GetChars;

public class StringUtils {

    public static String copyString(CharSequence charSequence) {
        int length = charSequence.length();
        char[] chars = new char[length];

        if (charSequence instanceof GetChars) {
            ((GetChars)charSequence).getChars(0, length, chars, 0);
        } else {
            for (int i = 0; i < length; i++) {
                chars[i] = charSequence.charAt(i);
            }
        }

        return new String(chars);
    }

    public static int getLeadingWhitespaceEnd(String string, int charStart, int charEnd) {
        for (int i = charStart; i < charEnd; i++) {
            if (!Character.isWhitespace(string.charAt(i))) {
                return i;
            }
        }

        return charEnd;
    }

    public static int getTrailingWhitespaceStart(String string, int charStart, int charEnd) {
        for (int i = charEnd - 1; i >= charStart; i--) {
            if (!Character.isWhitespace(string.charAt(i))) {
                return i + 1;
            }
        }

        return charStart;
    }
}
