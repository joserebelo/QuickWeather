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

package com.ominous.quickweather.activity;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.BitmapFactory;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;

import com.ominous.quickweather.R;
import com.ominous.quickweather.data.WeatherDatabase;
import com.ominous.quickweather.data.WeatherLogic;
import com.ominous.quickweather.data.WeatherModel;
import com.ominous.quickweather.data.WeatherResponseOneCall;
import com.ominous.quickweather.location.WeatherLocationManager;
import com.ominous.quickweather.util.ColorUtils;
import com.ominous.quickweather.util.SnackbarHelper;
import com.ominous.quickweather.util.WeatherPreferences;
import com.ominous.quickweather.view.WeatherCardRecyclerView;
import com.ominous.tylerutils.async.Promise;
import com.ominous.tylerutils.browser.CustomTabs;
import com.ominous.tylerutils.http.HttpException;
import com.ominous.tylerutils.util.LocaleUtils;
import com.ominous.tylerutils.util.WindowUtils;

import org.json.JSONException;

import java.io.IOException;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

public class ForecastActivity extends AppCompatActivity {
    public static final String EXTRA_DATE = "EXTRA_DATE";
    private WeatherCardRecyclerView weatherCardRecyclerView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private Toolbar toolbar;
    private ImageView toolbarMyLocation;
    private ForecastViewModel forecastViewModel;
    private final ActivityResultLauncher<String[]> requestPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), r -> forecastViewModel.obtainWeatherAsync());
    private Date date = null;
    private SnackbarHelper snackbarHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        initActivity();

        if (WeatherPreferences.isInitialized()) {
            onReceiveIntent(getIntent());

            initViews();
            initViewModel();
        } else {
            ContextCompat.startActivity(this, new Intent(this, SettingsActivity.class), null);
            finish();
        }
    }

    private void initActivity() {
        ColorUtils.initialize(this);//Initializing after Activity created to get day/night properly

        setTaskDescription(
                Build.VERSION.SDK_INT >= 28 ?
                        new ActivityManager.TaskDescription(
                                getString(R.string.app_name),
                                R.mipmap.ic_launcher_round,
                                ContextCompat.getColor(this, R.color.color_app_accent)) :
                        new ActivityManager.TaskDescription(
                                getString(R.string.app_name),
                                BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher_round),
                                ContextCompat.getColor(this, R.color.color_app_accent))
        );
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        onReceiveIntent(intent);
    }

    private void onReceiveIntent(Intent intent) {
        Bundle bundle;
        long timestamp;

        if ((bundle = intent.getExtras()) != null &&
                (timestamp = bundle.getLong(EXTRA_DATE)) != 0) {
            date = new Date(timestamp);
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        this.overridePendingTransition(R.anim.slide_left_in, R.anim.slide_right_out);
    }

    @Override
    protected void onResume() {
        super.onResume();

        ColorUtils.setNightMode(this);

        forecastViewModel.obtainWeatherAsync();
    }

    private void initViewModel() {
        forecastViewModel = new ViewModelProvider(this)
                .get(ForecastActivity.ForecastViewModel.class);

        forecastViewModel.setDate(date);

        forecastViewModel.getWeatherModel().observe(this, weatherModel -> {
            swipeRefreshLayout.setRefreshing(
                    weatherModel.status == WeatherModel.WeatherStatus.UPDATING ||
                            weatherModel.status == WeatherModel.WeatherStatus.OBTAINING_LOCATION);

            snackbarHelper.dismiss();

            switch (weatherModel.status) {
                case SUCCESS:
                    Promise.create((a) -> {
                        WeatherDatabase.WeatherLocation weatherLocation = WeatherDatabase.getInstance(this).locationDao().getSelected();

                        new Handler(Looper.getMainLooper()).post(() -> updateWeather(weatherLocation, weatherModel));
                    });

                    break;
                case OBTAINING_LOCATION:
                    snackbarHelper.notifyObtainingLocation();
                    break;
                case ERROR_OTHER:
                    snackbarHelper.logError(weatherModel.errorMessage, null);

                    swipeRefreshLayout.setRefreshing(false);
                    break;
                case ERROR_LOCATION_ACCESS_DISALLOWED:
                    snackbarHelper.notifyLocPermDenied(requestPermissionLauncher);
                    break;
                case ERROR_LOCATION_DISABLED:
                    snackbarHelper.notifyLocDisabled();
                    break;
            }
        });
    }

    private void updateWeather(WeatherDatabase.WeatherLocation weatherLocation, WeatherModel weatherModel) {
        long thisDate = LocaleUtils.getStartOfDay(weatherModel.date, TimeZone.getTimeZone(weatherModel.responseOneCall.timezone));

        boolean isToday = false;
        WeatherResponseOneCall.DailyData thisDailyData = null;
        for (int i = 0, l = weatherModel.responseOneCall.daily.length; i < l; i++) {
            WeatherResponseOneCall.DailyData dailyData = weatherModel.responseOneCall.daily[i];

            if (LocaleUtils.getStartOfDay(new Date(dailyData.dt * 1000),
                    TimeZone.getTimeZone(weatherModel.responseOneCall.timezone)) == thisDate) {
                thisDailyData = dailyData;

                isToday = i == 0;
                i = l;
            }
        }

        if (thisDailyData != null) {
            Calendar calendar = Calendar.getInstance(Locale.getDefault());
            calendar.setTimeInMillis(thisDailyData.dt * 1000);

            toolbar.setTitle(getString(R.string.format_forecast_title,
                    isToday ? getString(R.string.text_today) : calendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.getDefault()),
                    weatherLocation.isCurrentLocation ? getString(R.string.text_current_location) : weatherLocation.name));

            toolbar.setContentDescription(getString(R.string.format_forecast_title,
                    isToday ? getString(R.string.text_today) : calendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.getDefault()),
                    weatherLocation.isCurrentLocation ? getString(R.string.text_current_location) : weatherLocation.name));

            weatherCardRecyclerView.update(weatherModel);

            int color = ColorUtils.getColorFromTemperature((thisDailyData.temp.min + thisDailyData.temp.max) / 2, false);
            int darkColor = ColorUtils.getDarkenedColor(color);
            int textColor = ColorUtils.getTextColor(color);

            toolbar.setBackgroundColor(color);
            toolbar.setTitleTextColor(textColor);

            if (weatherLocation.isCurrentLocation) {
                toolbarMyLocation.setImageTintList(ColorStateList.valueOf(textColor));
                toolbarMyLocation.setVisibility(View.VISIBLE);
            } else {
                toolbarMyLocation.setVisibility(View.GONE);
            }

            Drawable navIcon = toolbar.getNavigationIcon();

            if (navIcon != null) {
                navIcon.setColorFilter(textColor, PorterDuff.Mode.SRC_IN);
                toolbar.setNavigationIcon(navIcon);
            }

            getWindow().setStatusBarColor(darkColor);
            getWindow().setNavigationBarColor(color);

            CustomTabs.getInstance(this).setColor(color);

            WindowUtils.setLightNavBar(getWindow(), textColor == ColorUtils.COLOR_TEXT_BLACK);
        }
    }

    private void initViews() {
        setContentView(R.layout.activity_forecast);

        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        toolbar = findViewById(R.id.toolbar);
        toolbarMyLocation = findViewById(R.id.toolbar_mylocation_indicator);
        weatherCardRecyclerView = findViewById(R.id.weather_card_recycler_view);

        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();

        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setDisplayShowHomeEnabled(true);
        }

        toolbar.setNavigationOnClickListener((a) -> onBackPressed());

        swipeRefreshLayout.setOnRefreshListener(() -> forecastViewModel.obtainWeatherAsync());

        snackbarHelper = new SnackbarHelper(findViewById(R.id.coordinator_layout));
    }

    public static class ForecastViewModel extends AndroidViewModel {
        private MutableLiveData<WeatherModel> weatherModelLiveData;
        private Date date;

        public ForecastViewModel(@NonNull Application application) {
            super(application);
        }

        public void setDate(Date date) {
            this.date = date;
        }

        public MutableLiveData<WeatherModel> getWeatherModel() {
            if (weatherModelLiveData == null) {
                weatherModelLiveData = new MutableLiveData<>();
            }

            return weatherModelLiveData;
        }

        private void obtainWeatherAsync() {
            Promise.create((a) -> {
                weatherModelLiveData.postValue(new WeatherModel(null, null, WeatherModel.WeatherStatus.UPDATING, null, date));

                WeatherModel weatherModel = new WeatherModel();
                weatherModel.status = WeatherModel.WeatherStatus.ERROR_OTHER;

                try {
                    weatherModel = WeatherLogic.getForecastWeather(getApplication(), false);

                    if (weatherModel.location == null) {
                        this.weatherModelLiveData.postValue(new WeatherModel(null, null, WeatherModel.WeatherStatus.OBTAINING_LOCATION, null, date));

                        weatherModel = WeatherLogic.getForecastWeather(getApplication(), true);
                    }

                    if (weatherModel.location == null) {
                        weatherModel.errorMessage = getApplication().getString(R.string.error_null_location);
                    } else if (weatherModel.responseOneCall == null || weatherModel.responseOneCall.current == null ||
                            weatherModel.responseForecast == null || weatherModel.responseForecast.list == null) {
                        weatherModel.errorMessage = getApplication().getString(R.string.error_null_response);
                    } else {
                        weatherModel.status = WeatherModel.WeatherStatus.SUCCESS;
                    }
                } catch (WeatherLocationManager.LocationPermissionNotAvailableException e) {
                    weatherModel.status = WeatherModel.WeatherStatus.ERROR_LOCATION_ACCESS_DISALLOWED;
                    weatherModel.errorMessage = getApplication().getString(R.string.snackbar_background_location);
                } catch (WeatherLocationManager.LocationDisabledException e) {
                    weatherModel.status = WeatherModel.WeatherStatus.ERROR_LOCATION_DISABLED;
                    weatherModel.errorMessage = getApplication().getString(R.string.error_gps_disabled);
                } catch (IOException e) {
                    weatherModel.errorMessage = getApplication().getString(R.string.error_connecting_api);
                } catch (JSONException e) {
                    weatherModel.errorMessage = getApplication().getString(R.string.error_unexpected_api_result);
                } catch (InstantiationException | IllegalAccessException e) {
                    weatherModel.errorMessage = getApplication().getString(R.string.error_creating_result);
                } catch (HttpException e) {
                    weatherModel.errorMessage = e.getMessage();
                }

                weatherModel.date = date;
                this.weatherModelLiveData.postValue(weatherModel);
            });
        }
    }
}