/*
 *   Copyright 2019 - 2026 Tyler Williamson
 *
 *   This file is part of QuickWeather.
 *
 *   QuickWeather is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   QuickWeather is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with QuickWeather.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.ominous.quickweather.pref;

public interface IPreferenceEnum {
    static <T extends Enum<T> & IPreferenceEnum> T from(String value, T defaultValue) {
        Object valuesObj = defaultValue.getClass().getEnumConstants();

        if (valuesObj != null) {
            try {
                //noinspection unchecked
                T[] values = (T[]) valuesObj;

                for (T v : values) {
                    if (v.getValue().equals(value)) {
                        return v;
                    }
                }
            } catch (ClassCastException e) {
                return defaultValue;
            }
        }

        return defaultValue;
    }

    String getValue();
}
