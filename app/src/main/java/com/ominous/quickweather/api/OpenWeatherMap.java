/*
 *     Copyright 2019 - 2022 Tyler Williamson
 *
 *     This file is part of QuickWeather.
 *
 *     QuickWeather is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     QuickWeather is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with QuickWeather.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.ominous.quickweather.api;

import android.util.Pair;

import com.ominous.quickweather.data.WeatherResponseForecast;
import com.ominous.quickweather.data.WeatherResponseOneCall;
import com.ominous.tylerutils.http.HttpException;
import com.ominous.tylerutils.http.HttpRequest;
import com.ominous.tylerutils.util.JsonUtils;
import com.ominous.tylerutils.work.ParallelThreadManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import androidx.annotation.NonNull;

public class OpenWeatherMap {
    private static final String uriFormatOneCall = "https://api.openweathermap.org/data/%5$s/onecall?appid=%1$s&lat=%2$f&lon=%3$f&lang=%4$s&units=imperial";
    private static final String uriFormatForecast = "https://api.openweathermap.org/data/2.5/forecast?appid=%1$s&lat=%2$f&lon=%3$f&lang=%4$s&units=imperial";
    private static final String uriFormatWeather = "https://api.openweathermap.org/data/2.5/weather?appid=%1$s&lat=%2$f&lon=%3$f&lang=%4$s&units=imperial";
    private static final Map<Pair<Double, Double>, WeatherResponseOneCall> oneCallResponseCache = new HashMap<>();
    private static final Map<Pair<Double, Double>, WeatherResponseForecast> forecastResponseCache = new HashMap<>();
    private static final int CACHE_EXPIRATION = 60 * 1000; //1 minute
    private static final int MAX_ATTEMPTS = 3;
    private static final int ATTEMPT_SLEEP_DURATION = 5000;

    public static WeatherResponseOneCall getWeatherOneCall(@NonNull APIVersion version, String apiKey, Pair<Double, Double> locationKey, boolean useCache)
            throws IOException, JSONException, InstantiationException, IllegalAccessException, HttpException {
        if (version == APIVersion.WEATHER_2_5) {
            throw new OpenWeatherMapException("Invalid OneCall API Option: " + version.name());
        }

        Calendar now = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
        WeatherResponseOneCall newWeather = null;
        Pair<Double, Double> newLocationKey = new Pair<>(
                BigDecimal.valueOf(locationKey.first).setScale(3, RoundingMode.HALF_UP).doubleValue(),
                BigDecimal.valueOf(locationKey.second).setScale(3, RoundingMode.HALF_UP).doubleValue()
        );

        if (useCache && oneCallResponseCache.containsKey(newLocationKey)) {
            WeatherResponseOneCall previousWeather = oneCallResponseCache.get(newLocationKey);

            if (previousWeather != null && now.getTimeInMillis() - previousWeather.timestamp < CACHE_EXPIRATION) {
                return previousWeather;
            }
        }

        int attempt = 0;
        HttpException lastException = null;

        do {
            try {
                newWeather = JsonUtils.deserialize(WeatherResponseOneCall.class, new JSONObject(
                        new HttpRequest(
                                String.format(Locale.US, uriFormatOneCall, apiKey,
                                        newLocationKey.first,
                                        newLocationKey.second,
                                        getLang(Locale.getDefault()),
                                        version == APIVersion.ONECALL_2_5 ? "2.5" : "3.0"))
                                .addHeader("User-Agent", "QuickWeather - https://play.google.com/store/apps/details?id=com.ominous.quickweather")
                                .fetch()));
            } catch (HttpException e) {
                lastException = e;
                try {
                    Thread.sleep(ATTEMPT_SLEEP_DURATION);
                } catch (InterruptedException ie) {
                    ie.printStackTrace();
                }
            } catch (IOException | JSONException | InstantiationException | IllegalAccessException e) {
                throw e;
            } catch (Throwable e) {
                throw new RuntimeException("Uncaught Exception occurred");
            }
        } while (newWeather == null && attempt++ < MAX_ATTEMPTS);

        if (newWeather == null && lastException != null) {
            throw lastException;
        }

        oneCallResponseCache.put(newLocationKey, newWeather);
        return newWeather;
    }

    public static WeatherResponseOneCall getWeatherOneCall(@NonNull APIVersion version, String apiKey, Pair<Double, Double> locationKey)
            throws IOException, JSONException, InstantiationException, IllegalAccessException, HttpException {
        return getWeatherOneCall(version, apiKey, locationKey, true);
    }

    public static WeatherResponseForecast getWeatherForecast(String apiKey, Pair<Double, Double> locationKey)
            throws IOException, JSONException, InstantiationException, IllegalAccessException, HttpException {
        Calendar now = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
        WeatherResponseForecast newWeather = null;
        Pair<Double, Double> newLocationKey = new Pair<>(
                BigDecimal.valueOf(locationKey.first).setScale(3, RoundingMode.HALF_UP).doubleValue(),
                BigDecimal.valueOf(locationKey.second).setScale(3, RoundingMode.HALF_UP).doubleValue()
        );

        if (oneCallResponseCache.containsKey(newLocationKey)) {
            WeatherResponseForecast previousWeather = forecastResponseCache.get(newLocationKey);

            if (previousWeather != null && now.getTimeInMillis() - previousWeather.timestamp * 1000 < CACHE_EXPIRATION) {
                return previousWeather;
            }
        }

        int attempt = 0;
        HttpException lastException = null;

        do {
            try {
                newWeather = JsonUtils.deserialize(WeatherResponseForecast.class, new JSONObject(
                        new HttpRequest(
                                String.format(Locale.US, uriFormatForecast, apiKey,
                                        newLocationKey.first,
                                        newLocationKey.second,
                                        getLang(Locale.getDefault())))
                                .addHeader("User-Agent", "QuickWeather - https://play.google.com/store/apps/details?id=com.ominous.quickweather")
                                .fetch()));
            } catch (HttpException e) {
                lastException = e;
                try {
                    Thread.sleep(ATTEMPT_SLEEP_DURATION);
                } catch (InterruptedException ie) {
                    ie.printStackTrace();
                }
            } catch (IOException | JSONException | InstantiationException | IllegalAccessException e) {
                throw e;
            } catch (Throwable e) {
                throw new RuntimeException("Uncaught Exception occurred");
            }
        } while (newWeather == null && attempt++ < MAX_ATTEMPTS);

        if (newWeather == null && lastException != null) {
            throw lastException;
        }

        forecastResponseCache.put(newLocationKey, newWeather);
        return newWeather;
    }

    public static String getLang(Locale locale) {
        String lang = locale.getLanguage();

        if (lang.equals("pt") && locale.getCountry().equals("BR")) {
            lang = "pt_br";
        } else if (locale.equals(Locale.CHINESE) || locale.equals(Locale.SIMPLIFIED_CHINESE)) {
            lang = "zh_cn";
        } else if (locale.equals(Locale.TRADITIONAL_CHINESE)) {
            lang = "zh_tw";
        }

        return lang.isEmpty() ? "en" : lang;
    }

    public static APIVersion determineApiVersion(String apiKey) throws OpenWeatherMapException {
        final HashMap<APIVersion, Boolean> results = new HashMap<>();

        try {
            ParallelThreadManager.execute(
                    () -> {
                        try {
                            new HttpRequest(
                                    String.format(Locale.US, uriFormatOneCall, apiKey,
                                            33.749,
                                            -84.388,
                                            getLang(Locale.getDefault()),
                                            "2.5"))
                                    .addHeader("User-Agent", "QuickWeather - https://play.google.com/store/apps/details?id=com.ominous.quickweather")
                                    .fetch();

                            results.put(APIVersion.ONECALL_2_5, true);
                        } catch (HttpException e) {
                            results.put(APIVersion.ONECALL_2_5, false);
                        } catch (IOException e) {
                            results.put(APIVersion.ONECALL_2_5, null);
                        }
                    },
                    () -> {
                        try {
                            new HttpRequest(
                                    String.format(Locale.US, uriFormatOneCall, apiKey,
                                            33.749,
                                            -84.388,
                                            getLang(Locale.getDefault()),
                                            "3.0"))
                                    .addHeader("User-Agent", "QuickWeather - https://play.google.com/store/apps/details?id=com.ominous.quickweather")
                                    .fetch();

                            results.put(APIVersion.ONECALL_3_0, true);
                        } catch (HttpException e) {
                            results.put(APIVersion.ONECALL_3_0, false);
                        } catch (IOException e) {
                            results.put(APIVersion.ONECALL_3_0, null);
                        }
                    },
                    () -> {
                        try {
                            new HttpRequest(
                                    String.format(Locale.US, uriFormatWeather, apiKey,
                                            33.749,
                                            -84.388,
                                            getLang(Locale.getDefault())
                                    ))
                                    .addHeader("User-Agent", "QuickWeather - https://play.google.com/store/apps/details?id=com.ominous.quickweather")
                                    .fetch();

                            results.put(APIVersion.WEATHER_2_5, true);
                        } catch (HttpException e) {
                            results.put(APIVersion.WEATHER_2_5, false);
                        } catch (IOException e) {
                            results.put(APIVersion.WEATHER_2_5, null);
                        }
                    }
            );
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        if (results.get(APIVersion.ONECALL_2_5) == null ||
                results.get(APIVersion.ONECALL_3_0) == null ||
                results.get(APIVersion.WEATHER_2_5) == null
        ) {
            throw new OpenWeatherMapException("Could not connect to OpenWeatherMap");
        }

        for (APIVersion option : new APIVersion[]{APIVersion.ONECALL_2_5, APIVersion.ONECALL_3_0, APIVersion.WEATHER_2_5}) {
            if (Boolean.TRUE.equals(results.get(option))) {
                return option;
            }
        }

        return null;
    }

    public enum PrecipType {
        RAIN,
        MIX,
        SNOW
    }

    public enum AlertSeverity {
        ADVISORY,
        WATCH,
        WARNING
    }

    public enum APIVersion {
        ONECALL_3_0,
        ONECALL_2_5,
        WEATHER_2_5
    }

    public static class OpenWeatherMapException extends RuntimeException {
        public OpenWeatherMapException(String message) {
            super(message);
        }
    }
}