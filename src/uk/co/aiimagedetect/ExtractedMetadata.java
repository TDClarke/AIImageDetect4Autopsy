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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Everything the extractor could read from the container structure of one image file. */
public final class ExtractedMetadata {

    private static final int MAX_FIELD_CHARS = 50_000;
    private static final int MAX_SEARCH_CHARS = 4_000_000;

    String container = "unknown";
    final List<MetaField> fields = new ArrayList<>();
    final List<String> c2paStrings = new ArrayList<>();
    boolean c2paPresent;
    boolean possibleC2paMarker;
    final List<String> notes = new ArrayList<>();

    void add(String source, String key, String value) {
        if (value == null) {
            return;
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return;
        }
        if (v.length() > MAX_FIELD_CHARS) {
            v = v.substring(0, MAX_FIELD_CHARS);
        }
        fields.add(new MetaField(source, key, v));
    }

    public String getContainer() { return container; }
    public List<MetaField> getFields() { return Collections.unmodifiableList(fields); }
    public boolean hasC2pa() { return c2paPresent; }
    public boolean hasPossibleC2paMarker() { return possibleC2paMarker; }
    public List<String> getNotes() { return Collections.unmodifiableList(notes); }

    /** @return all metadata text (fields and C2PA strings) as one searchable block */
    String searchText() {
        StringBuilder sb = new StringBuilder();
        for (MetaField f : fields) {
            sb.append(f.source).append('/').append(f.key).append(": ").append(f.value).append('\n');
            if (sb.length() > MAX_SEARCH_CHARS) {
                return sb.toString();
            }
        }
        for (String s : c2paStrings) {
            sb.append(s).append('\n');
            if (sb.length() > MAX_SEARCH_CHARS) {
                break;
            }
        }
        return sb.toString();
    }
}
