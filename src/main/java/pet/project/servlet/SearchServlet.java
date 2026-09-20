package pet.project.servlet;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import pet.project.dao.LocationDao;
import pet.project.dao.SessionDao;
import pet.project.exception.CookieNotFoundException;
import pet.project.exception.InvalidParameterException;
import pet.project.exception.SessionExpiredException;
import pet.project.exception.UnauthorizedSearchException;
import pet.project.exception.api.GeocodingApiCallException;
import pet.project.model.Location;
import pet.project.model.Session;
import pet.project.model.User;
import pet.project.model.api.LocationApiResponse;
import pet.project.service.WeatherApiService;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@WebServlet("/search")
public class SearchServlet extends WeatherTrackerBaseServlet {
    private final SessionDao sessionDao;
    private final LocationDao locationDao;
    private final WeatherApiService weatherApiService;

    public SearchServlet() {
        this(new SessionDao(), new LocationDao(), new WeatherApiService());
    }

    SearchServlet(SessionDao sessionDao, LocationDao locationDao, WeatherApiService weatherApiService) {
        this.sessionDao = sessionDao;
        this.locationDao = locationDao;
        this.weatherApiService = weatherApiService;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException, SessionExpiredException, InvalidParameterException, GeocodingApiCallException, UnauthorizedSearchException {
        log.info("Finding cookie with session id");
        Cookie[] cookies = req.getCookies();
        Cookie cookie = findCookieByName(cookies, "sessionId")
                .orElseThrow(() -> new UnauthorizedSearchException("Attempt to search location without authorization"));

        UUID sessionId = UUID.fromString(cookie.getValue());

        log.info("Finding session: " + sessionId);
        Session session = sessionDao.findById(sessionId)
                .orElseThrow(() -> new SessionExpiredException("Session: " + sessionId + " has expired"));

        if (isSessionExpired(session)) {
            throw new SessionExpiredException("Session: " + sessionId + " has expired");
        }

        User user = session.getUser();

        String searchQuery = req.getParameter("q");

        if (searchQuery == null || searchQuery.isBlank()) {
            throw new InvalidParameterException("Search query is invalid");
        }

        log.info("Calling openweather geocoding API: " + searchQuery);
        List<LocationApiResponse> foundLocations = weatherApiService.getLocationsByName(searchQuery);

        context.setVariable("login", user.getLogin());
        context.setVariable("foundLocations", foundLocations);

        log.info("Processing search page");
        templateEngine.process("search", context, resp.getWriter());
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException, CookieNotFoundException, SessionExpiredException, InvalidParameterException {
        log.info("Finding cookie with session id");
        Cookie[] cookies = req.getCookies();
        Cookie cookie = findCookieByName(cookies, "sessionId")
                .orElseThrow(() -> new CookieNotFoundException("Cookie with session id is not found"));

        UUID sessionId = UUID.fromString(cookie.getValue());

        log.info("Finding session: " + sessionId);
        Session session = sessionDao.findById(sessionId)
                .orElseThrow(() -> new SessionExpiredException("Session: " + sessionId + " has expired"));

        if (isSessionExpired(session)) {
            throw new SessionExpiredException("Session: " + sessionId + " has expired");
        }

        User user = session.getUser();

        String name = req.getParameter("name");
        String latitudeParam = req.getParameter("latitude");
        String longitudeParam = req.getParameter("longitude");

        if (name == null || name.isBlank()) {
            throw new InvalidParameterException("Parameter name is invalid");
        }
        if (latitudeParam == null || latitudeParam.isBlank()) {
            throw new InvalidParameterException("Parameter latitude is invalid");
        }
        if (longitudeParam == null || longitudeParam.isBlank()) {
            throw new InvalidParameterException("Parameter longitude is invalid");
        }

        double latitude;
        double longitude;
        try {
            latitude = Double.parseDouble(latitudeParam);
            longitude = Double.parseDouble(longitudeParam);
        } catch (NumberFormatException exception) {
            throw new InvalidParameterException("Latitude or longitude parameter is invalid");
        }

        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90) {
            throw new InvalidParameterException("Parameter latitude is invalid");
        }
        if (!Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new InvalidParameterException("Parameter longitude is invalid");
        }

        log.info("Finding location: lat=" + latitude + " lon=" + longitude);
        Optional<Location> locationOptional = locationDao.findByCoordinates(latitude, longitude);

        if (locationOptional.isPresent()) {
            log.info("Location is found: " + locationOptional.get().getId() + " Adding user: " + user.getId() + " to location: " + locationOptional.get().getId());
            Location location = locationOptional.get();

            List<User> users = location.getUsers();
            users.add(user);
            location.setUsers(users);

            locationDao.update(location);
        } else {
            log.info("Location is not found: creating new location");
            Location location = new Location(
                    name,
                    List.of(user),
                    latitude,
                    longitude
            );
            locationDao.save(location);
        }

        log.info("Adding location to the tracked list is successful: redirecting to the home page");
        resp.sendRedirect(req.getContextPath());
    }
}
