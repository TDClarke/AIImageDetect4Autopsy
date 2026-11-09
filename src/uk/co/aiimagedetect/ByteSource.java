/*
 * AIImageDetect4Autopsy
 * Copyright (C) 2014 Rajmund Witt (SmutDetect4Autopsy, from which the module
 * structure of this project is derived)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package uk.co.aiimagedetect;

import java.io.IOException;

/** Random-access, read-only view of a file's bytes (keeps the core free of Autopsy classes). */
public interface ByteSource {

    long length();

    /** @return bytes read (may be fewer than len), or -1 at end of data */
    int read(byte[] buf, long offset, int len) throws IOException;

    static ByteSource of(final byte[] data) {
        return new ByteSource() {
            @Override
            public long length() {
                return data.length;
            }

            @Override
            public int read(byte[] buf, long offset, int len) {
                if (offset >= data.length) {
                    return -1;
                }
                int n = (int) Math.min(len, data.length - offset);
                System.arraycopy(data, (int) offset, buf, 0, n);
                return n;
            }
        };
    }
}
