package com.grash.controller;

import com.grash.dto.DateRange;
import com.grash.dto.ReadingHistogramDTO;
import com.grash.dto.ReadingPatchDTO;
import com.grash.dto.SuccessResponse;
import com.grash.exception.CustomException;
import com.grash.model.*;
import com.grash.model.enums.PlanFeatures;
import com.grash.service.*;
import com.grash.utils.Helper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@RestController
@RequestMapping("/readings")
@Tag(name = "Readings", description = "Operations on meter readings")
@RequiredArgsConstructor
public class ReadingController {

    private final MeterService meterService;
    private final ReadingService readingService;
    private final UserService userService;


    @GetMapping("/meter/{id}")
    @PreAuthorize("permitAll()")
    public Collection<Reading> getByMeter(@PathVariable("id") Long id, HttpServletRequest req) {
        User user = userService.whoami(req);
        Optional<Meter> optionalMeter = meterService.findById(id);
        if (optionalMeter.isPresent()) {
            if (!optionalMeter.get().canBeViewedBy(user))
                throw new CustomException("Access denied", HttpStatus.FORBIDDEN);
            return readingService.findByMeter(id);
        } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);
    }

    @PostMapping("/meter/{id}/histogram")
    @PreAuthorize("permitAll()")
    @Operation(summary = "Get histogram data for a meter within a date range (max 30 points)")
    public List<ReadingHistogramDTO> getHistogram(
            @PathVariable("id") Long id,
            @RequestBody DateRange dateRange,
            HttpServletRequest req) {
        User user = userService.whoami(req);
        Optional<Meter> optionalMeter = meterService.findById(id);
        if (optionalMeter.isEmpty()) {
            throw new CustomException("Meter not found", HttpStatus.NOT_FOUND);
        }
        if (!optionalMeter.get().canBeViewedBy(user))
            throw new CustomException("Access denied", HttpStatus.FORBIDDEN);
        if (dateRange.getStart() == null || dateRange.getEnd() == null) {
            throw new CustomException("Start and end dates are required", HttpStatus.BAD_REQUEST);
        }
        if (dateRange.getStart().after(dateRange.getEnd())) {
            throw new CustomException("Start date must be before end date", HttpStatus.BAD_REQUEST);
        }
        return readingService.getHistogramData(id, dateRange.getStart(),
                dateRange.getEnd(), user.getCompany().getCompanySettings().getGeneralPreferences().getTimeZone());
    }

    @PostMapping("")
    @PreAuthorize("hasRole('ROLE_CLIENT')")
    Reading create(@Parameter(description = "Reading data to create") @Valid @RequestBody Reading readingReq,
                   HttpServletRequest req) {
        User user = userService.whoami(req);
        if (!user.getCompany().getSubscription().getSubscriptionPlan().getFeatures().contains(PlanFeatures.METER))
            throw new CustomException("Access denied", HttpStatus.FORBIDDEN);
        Optional<Meter> optionalMeter = meterService.findById(readingReq.getMeter().getId());
        if (optionalMeter.isPresent()) {
            Meter meter = optionalMeter.get();
            if (!meter.canBeViewedBy(user))
                throw new CustomException("Access denied", HttpStatus.FORBIDDEN);
            Optional<Reading> optionalLastReading = readingService.findLastByMeter(readingReq.getMeter().getId());
            if (optionalLastReading.isPresent()) {
                Reading lastReading = optionalLastReading.get();
                String timeZone = meter.getCompany()
                        .getCompanySettings()
                        .getGeneralPreferences()
                        .getTimeZone();
                LocalDate nextReading =
                        Helper.dateToLocalDate(lastReading.getCreatedAt()).plusDays(meter.getUpdateFrequency());
                if (LocalDate.now(ZoneId.of(timeZone)).isBefore(nextReading)) {
                    throw new CustomException("The update frequency has not been respected", HttpStatus.NOT_ACCEPTABLE);
                }
            }
            // The meter alarm used to run here, before the reading was even saved. It is a
            // consumer of the committed reading now; see MeterTriggerFanout.
            return readingService.create(readingReq);
        } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ROLE_CLIENT')")
    public Reading patch(@Parameter(description = "Reading fields to update") @Valid @RequestBody ReadingPatchDTO reading,
                         @PathVariable("id") Long id,
                         HttpServletRequest req) {
        User user = userService.whoami(req);
        Optional<Reading> optionalReading = readingService.findById(id);

        if (optionalReading.isPresent()) {
            Reading savedReading = optionalReading.get();
            if (!savedReading.getMeter().canBeViewedBy(user))
                throw new CustomException("Access denied", HttpStatus.FORBIDDEN);
            return readingService.update(id, reading);
        } else throw new CustomException("Reading not found", HttpStatus.NOT_FOUND);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ROLE_CLIENT')")
    public ResponseEntity<SuccessResponse> delete(@PathVariable("id") Long id, HttpServletRequest req) {
        User user = userService.whoami(req);

        Optional<Reading> optionalReading = readingService.findById(id);
        if (optionalReading.isPresent()) {
            if (!optionalReading.get().getMeter().canBeViewedBy(user))
                throw new CustomException("Access denied", HttpStatus.FORBIDDEN);
            readingService.delete(id);
            return new ResponseEntity<>(new SuccessResponse(true, "Deleted successfully"),
                    HttpStatus.OK);
        } else throw new CustomException("Reading not found", HttpStatus.NOT_FOUND);
    }

}
