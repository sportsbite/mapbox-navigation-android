package com.mapbox.services.android.navigation.v5;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.support.annotation.FloatRange;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.mapbox.services.Experimental;
import com.mapbox.services.android.location.LostLocationEngine;
import com.mapbox.services.android.navigation.v5.listeners.NavigationEventListener;
import com.mapbox.services.android.navigation.v5.offroute.OffRouteListener;
import com.mapbox.services.android.navigation.v5.routeprogress.ProgressChangeListener;
import com.mapbox.services.android.navigation.v5.milestone.Milestone;
import com.mapbox.services.android.navigation.v5.milestone.MilestoneEventListener;
import com.mapbox.services.android.navigation.v5.routeprogress.RouteProgress;
import com.mapbox.services.android.telemetry.location.LocationEngine;
import com.mapbox.services.api.directions.v5.DirectionsCriteria;
import com.mapbox.services.api.directions.v5.MapboxDirections;
import com.mapbox.services.api.directions.v5.models.DirectionsResponse;
import com.mapbox.services.api.directions.v5.models.DirectionsRoute;
import com.mapbox.services.commons.models.Position;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import retrofit2.Callback;
import timber.log.Timber;

/**
 * This is an experimental API. Experimental APIs are quickly evolving and
 * might change or be removed in minor versions.
 *
 * @since 0.1.0
 */
@Experimental
public class MapboxNavigation implements MilestoneEventListener {

  // Navigation service variables
  private NavigationServiceConnection connection;
  private NavigationService navigationService;
  private MapboxNavigationOptions options;
  private Context context;
  private boolean isBound;

  // Navigation variables
  private CopyOnWriteArrayList<NavigationEventListener> navigationEventListeners;
  private CopyOnWriteArrayList<ProgressChangeListener> progressChangeListeners;
  private CopyOnWriteArrayList<OffRouteListener> offRouteListeners;
  private CopyOnWriteArrayList<MilestoneEventListener> milestoneEventListeners;
  private CopyOnWriteArrayList<Milestone> milestones;
  private LocationEngine locationEngine;
  private boolean snapToRoute;

  // Requesting route variables
  private DirectionsRoute route;
  private Position destination;
  private Float userBearing;
  private String accessToken;
  private Position origin;

  /*
   * Constructor
   */

  /**
   * Creates a new MapboxNavigation object.
   *
   * @param context     {@link Context} used for various things internally, cannot be null.
   * @param accessToken A valid Mapbox access token.
   * @since 0.1.0
   */
  public MapboxNavigation(@NonNull Context context, @NonNull String accessToken) {
    this(context, accessToken, new MapboxNavigationOptions());
  }

  /**
   * Creates a new MapboxNavigation object.
   *
   * @param context     {@link Context} used for various things internally, cannot be null.
   * @param accessToken A valid Mapbox access token.
   * @param options     a {@link MapboxNavigationOptions} with your customized options.
   * @since 0.2.0
   */
  public MapboxNavigation(@NonNull Context context, @NonNull String accessToken,
                          @NonNull MapboxNavigationOptions options) {
    Timber.d("MapboxNavigation initiated.");
    this.context = context;
    this.accessToken = accessToken;
    this.options = options;
    connection = new NavigationServiceConnection();
    isBound = false;
    navigationService = null;
    snapToRoute = true;
    navigationEventListeners = new CopyOnWriteArrayList<>();
    progressChangeListeners = new CopyOnWriteArrayList<>();
    offRouteListeners = new CopyOnWriteArrayList<>();
    milestoneEventListeners = new CopyOnWriteArrayList<>();
    milestones = new CopyOnWriteArrayList<>();

    if (options.defaultMilestonesEnabled()) {
      new DefaultMilestones(this);
    }
  }

  @Override
  public void onMilestoneEvent(RouteProgress routeProgress, String instruction, int identifier) {
    if (identifier == NavigationConstants.ARRIVAL_MILESTONE) {
      endNavigation();
    }
  }

  /*
   * Lifecycle
   */

  /**
   * It's required to include both the {@code onStop} and {@code onStart} inside your navigation activities identically
   * named lifecycle methods.
   *
   * @since 0.1.0
   */
  public void onStart() {
    Timber.d("MapboxNavigation onStart.");

    // Only bind if the service was previously started

    context.bindService(getServiceIntent(), connection, 0);
    isBound = true;
  }

  /**
   * It's required to include both the {@code onStop} and {@code onStart} inside your navigation activities identically
   * named lifecycle methods.
   *
   * @since 0.1.0
   */
  public void onStop() {
    Timber.d("MapboxNavigation onStop.");
    if (isBound) {
      Timber.d("unbindService called");
      context.unbindService(connection);
      isBound = false;
    }
  }

  public void onDestroy() {
    Timber.d("MapboxNavigation onDestroy.");
    context.stopService(getServiceIntent());
  }

  public void addMilestone(Milestone milestone) {
    milestones.add(milestone);
  }

  /*
   * Navigation setup methods
   */

  /**
   * Before starting a navigationService, it is required to setup a {@link LocationEngine} and pass it into your
   * instance of {@code MapboxNavigation}. If a locationEngine isn't provided, one will be created.
   *
   * @param locationEngine A {@link LocationEngine} used for the navigation session.
   */
  public void setLocationEngine(LocationEngine locationEngine) {
    this.locationEngine = locationEngine;
    if (isServiceAvailable()) {
      navigationService.setLocationEngine(getLocationEngine());
    }
  }

  public void addMilestoneEventListener(MilestoneEventListener milestoneEventListener) {
    milestoneEventListeners.add(milestoneEventListener);
  }

  public void removeMilestoneEventListener(MilestoneEventListener milestoneEventListeners) {
    this.milestoneEventListeners.remove(milestoneEventListeners);
  }

  /**
   * Optionally listen into when a new navigation event occurs. This listener can be used to listen into when the
   * navigation service is running in the background or not.
   *
   * @param navigationEventListener a new {@link NavigationEventListener} which will be notified when navigation events
   *                                occur.
   * @since 0.1.0
   */
  public void addNavigationEventListener(NavigationEventListener navigationEventListener) {
    if (!this.navigationEventListeners.contains(navigationEventListener)) {
      this.navigationEventListeners.add(navigationEventListener);
    }
  }

  public void removeNavigationEventListener(NavigationEventListener navigationEventListener) {
    this.navigationEventListeners.remove(navigationEventListener);
  }

  /**
   * Optionally, setup a progress change listener to be notified each time the users location has changed. For more
   * details see {@link ProgressChangeListener}.
   *
   * @param progressChangeListener a new {@link ProgressChangeListener} which will be notified when event occurs.
   * @since 0.1.0
   */
  public void addProgressChangeListener(ProgressChangeListener progressChangeListener) {
    if (!this.progressChangeListeners.contains(progressChangeListener)) {
      this.progressChangeListeners.add(progressChangeListener);
    }
  }

  public void removeProgressChangeListener(ProgressChangeListener progressChangeListener) {
    progressChangeListeners.remove(progressChangeListener);
  }

  public void addOffRouteListener(OffRouteListener offRouteListener) {
    if (!this.offRouteListeners.contains(offRouteListener)) {
      this.offRouteListeners.add(offRouteListener);
    }
  }

  public void removeOffRouteListener(OffRouteListener offRouteListener) {
    offRouteListeners.remove(offRouteListener);
  }

  /**
   * Disable or enable the snap to route for navigation, by default this is set to enable. When enabled, the snap to
   * route functionality of this SDK takes place and the user will snap to the route if the user is currently along the
   * route giving a better user experience. It's recommend to leave this enabled. Otherwise, the location used for
   * Navigation will be the actual GPS location which will typically be noisy and not follow the route.
   *
   * @param snapToRoute {@code boolean} true if you'd like snap to route be enabled, else false; defaults true.
   * @since 0.1.0
   */
  public void setSnapToRoute(boolean snapToRoute) {
    this.snapToRoute = snapToRoute;
  }

  /**
   * Call {@code startNavigation} passing in a {@link DirectionsRoute} object to begin a navigation session. It is
   * recommend to call {@link MapboxNavigation#getRoute(Position, Position, Callback)} before starting a navigation
   * session.
   *
   * @param route A {@link DirectionsRoute} that makes up the path your user will traverse along.
   * @since 0.1.0
   */
  public void startNavigation(DirectionsRoute route) {
    this.route = route;
    if (!isServiceAvailable()) {
      Timber.d("MapboxNavigation startNavigation called.");
      if (!isBound) {
        context.bindService(getServiceIntent(), connection, 0);
        isBound = true;
      }
      context.startService(getServiceIntent());
    }
  }

  /**
   * When a reroute's performed, use this API to pass in the new directions route.
   *
   * @param directionsRoute the new {@link DirectionsRoute}
   * @since 0.3.0
   */
  public void updateRoute(DirectionsRoute directionsRoute) {
    if (isServiceAvailable()) {
      navigationService.updateRoute(directionsRoute);
    }
  }

  /**
   * Call this method to end a navigation session before the user reaches their destination. There isn't a need to call
   * this once the user reaches their destination.
   *
   * @since 0.1.0
   */
  public void endNavigation() {
    if (isServiceAvailable()) {
      Timber.d("MapboxNavigation endNavigation called");
      navigationService.endNavigation();
      onStop();
      navigationService = null;
    }
  }

  /**
   * @param activity The activity being used for the navigation session.
   * @since 0.1.0
   */
  public void setupNotification(Activity activity) {
    if (isServiceAvailable()) {
      Timber.d("MapboxNavigation setting up notification.");
      navigationService.setupNotification(activity);
    }
  }

  /*
   * Directions API request methods
   */

  /**
   * Request navigation to acquire a route and notify your callback when a response comes in. A
   * {@link NavigationException} will be thrown if you haven't set your access token, origin or destination before
   * calling {@code getRoute}. It's advised to pass in the users bearing whenever possible for a more accurate
   * directions route.
   * <p>
   * If you'd like navigation to reroute when the user goes off-route, call this method with the updated information.
   *
   * @param callback    A callback of type {@link DirectionsResponse} which allows you to handle the Directions API
   *                    response.
   * @param origin      the starting position for the navigation session
   * @param userBearing provide the users bearing to continue the route in the users direction
   * @param destination the arrival position for the navigation session
   * @since 0.3.0
   */
  public void getRoute(@NonNull Position origin, @NonNull Position destination, @Nullable Float userBearing,
                       Callback<DirectionsResponse> callback) {
    getRoute(Arrays.asList(origin, destination), userBearing, callback);
  }

  /**
   * Request navigation to acquire a route and notify your callback when a response comes in. A
   * {@link NavigationException} will be thrown if you haven't set your access token, origin or destination before
   * calling {@code getRoute}. It's advised to pass in the users bearing whenever possible for a more accurate
   * directions route.
   * <p>
   * If you'd like navigation to reroute when the user goes off-route, call this method with the updated information.
   *
   * @param callback    A callback of type {@link DirectionsResponse} which allows you to handle the Directions API
   *                    response.
   * @param origin      the starting position for the navigation session
   * @param destination the arrival position for the navigation session
   * @since 0.1.0
   */
  public void getRoute(@NonNull Position origin, @NonNull Position destination, Callback<DirectionsResponse> callback) {
    getRoute(Arrays.asList(origin, destination), null, callback);
  }

  /**
   * Request navigation to acquire a route and notify your callback when a response comes in. A
   * {@link NavigationException} will be thrown if you haven't set your access token, origin or destination before
   * calling {@code getRoute}.
   *
   * @param callback    A callback of type {@link DirectionsResponse} which allows you to handle the Directions API
   *                    response.
   * @param coordinates list of {@link Position} which you'd like to have your route traverse to
   * @param userBearing provide the users bearing to continue the route in the users direction
   * @since 0.4.0
   */
  public void getRoute(@NonNull List<Position> coordinates, @Nullable Float userBearing,
                       Callback<DirectionsResponse> callback)
    throws NavigationException {
    this.origin = coordinates.get(0);
    this.destination = coordinates.get(coordinates.size() - 1);
    if (accessToken == null) {
      throw new NavigationException("A Mapbox access token must be passed into your MapboxNavigation instance before"
        + "calling getRoute");
    } else if (origin == null || destination == null) {
      throw new NavigationException("A origin and destination Position must be passed into your MapboxNavigation"
        + "instance before calling getRoute");
    }

    MapboxDirections.Builder directionsBuilder = new MapboxDirections.Builder()
      .setProfile(options.getDirectionsProfile())
      .setAccessToken(accessToken)
      .setOverview(DirectionsCriteria.OVERVIEW_FULL)
      .setAnnotation(DirectionsCriteria.ANNOTATION_CONGESTION)
      .setCoordinates(coordinates)
      .setSteps(true);

    // Optionally set the bearing and radiuses if the developer provider the user bearing. A tolerance of 90 degrees
    // is given.
    if (userBearing != null) {
      double[][] bearings = new double[coordinates.size()][0];
      bearings[0] = new double[] {(double) userBearing, 90};
      directionsBuilder.setBearings(bearings);

      double[] radiuses = new double[coordinates.size()];
      radiuses[0] = 50d;
      for (int i = 1; i < radiuses.length; i++) {
        radiuses[i] = Double.POSITIVE_INFINITY;
      }
    }
    directionsBuilder.build().enqueueCall(callback);
  }

  /**
   * Get the current origin {@link Position} that will be used for the beginning of the route. If no origin's provided,
   * null will be returned. An origin can be set using the
   * {@link MapboxNavigation#getRoute(Position, Position, Callback)} method.
   *
   * @return A {@link Position} object representing the origin of your route.
   * @since 0.1.0
   */
  public Position getOrigin() {
    return origin;
  }

  /**
   * Get the current destination {@link Position} that will be used for the end of the route. If no destinations's
   * provided, null will be returned. A destination can be set using the
   * {@link MapboxNavigation#getRoute(Position, Position, Callback)} method.
   *
   * @return A {@link Position} object representing the destination of your route.
   * @since 0.1.0
   */
  public Position getDestination() {
    return destination;
  }

  /**
   * Optional but recommended parameter that can be used to route a user in the correct orientation they are already
   * headed in. This will help prevent routing the user to begin the route by performing an illegal U-turn.
   *
   * @param userBearing {@code int} between 0 and 260 representing the users current bearing.
   * @since 0.1.0
   */
  public void setUserOriginBearing(@FloatRange(from = 0, to = 360) float userBearing) {
    this.userBearing = userBearing;
  }

  /**
   * Gets the current user bearing that will be used when
   * {@link MapboxNavigation#getRoute(Position, Position, Callback)}'s called. If no value's set, the return value will
   * be {@code null}.
   *
   * @return A value between 0 and 360 or null if the userOriginBearing hasn't been set.
   * @since 0.1.0
   */
  public float getUserOriginBearing() {
    return userBearing;
  }

  /**
   * Get the currently used {@link MapboxNavigationOptions}, if one isn't set, the default values will be used.
   *
   * @return the set MapboxNavigationOptions object
   * @since 0.3.0
   */
  public MapboxNavigationOptions getMapboxNavigationOptions() {
    return options;
  }

  /*
   * Service methods
   */

  private LocationEngine getLocationEngine() {
    // Use the LostLocationEngine if none is provided
    return locationEngine == null ? new LostLocationEngine(context) : locationEngine;
  }

  private boolean isServiceAvailable() {
    boolean isAvailable = (navigationService != null);
    Timber.d("MapboxNavigation service available: %b", isAvailable);
    return isAvailable;
  }

  private Intent getServiceIntent() {
    return new Intent(context, NavigationService.class);
  }

  private class NavigationServiceConnection implements ServiceConnection {
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
      Timber.d("Connected to service.");
      NavigationService.LocalBinder binder = (NavigationService.LocalBinder) service;
      navigationService = binder.getService();
      navigationService.setLocationEngine(getLocationEngine());
      navigationService.setOptions(options);
      navigationService.setNavigationEventListeners(navigationEventListeners);
      navigationService.setMilestones(milestones);
      navigationService.setMilestoneEventListeners(milestoneEventListeners);

      milestoneEventListeners.add(MapboxNavigation.this);

      navigationService.setProgressChangeListeners(progressChangeListeners);
      navigationService.setOffRouteListeners(offRouteListeners);
      navigationService.setSnapToRoute(snapToRoute);
      navigationService.startRoute(route);
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {
      Timber.d("Disconnected from service.");
      navigationService = null;
    }
  }
}
