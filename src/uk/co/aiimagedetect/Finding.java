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

/** One indicator found in the metadata, with the points it adds to the triage score. */
public final class Finding {

    public final String type;
    public final String strength;
    public final String description;
    public final int points;

    Finding(String type, String strength, String description, int points) {
        this.type = type;
        this.strength = strength;
        this.description = description;
        this.points = points;
    }

    @Override
    public String toString() {
        return "[" + strength + "] " + description;
    }
}
