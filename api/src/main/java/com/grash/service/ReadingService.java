package com.grash.service;

import com.grash.automation.event.CurrentActor;
import com.grash.automation.event.SemanticEventPublisher;
import com.grash.dto.DateRange;
import com.grash.dto.ReadingHistogramDTO;
import com.grash.dto.ReadingPatchDTO;
import com.grash.event.fanout.ReadingRecorded;
import com.grash.exception.CustomException;
import com.grash.mapper.ReadingMapper;
import com.grash.model.Meter;
import com.grash.model.Reading;
import com.grash.model.User;
import com.grash.model.enums.PlanFeatures;
import com.grash.repository.ReadingRepository;
import com.grash.utils.Helper;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class ReadingService {
    private final ReadingRepository readingRepository;
    private final ReadingMapper readingMapper;
    private final LicenseService licenseService;
    private final SemanticEventPublisher semanticEventPublisher;
    private MeterService meterService;

    @Autowired
    public void setDeps(@Lazy MeterService meterService
    ) {
        this.meterService = meterService;
    }

    /**
     * Writes a reading and announces it.
     *
     * <p>The announcement is what moved the meter alarm out of the request path: the threshold
     * check, the work order it raises, the notification and the webhook are a consumer now
     * ({@code MeterTriggerFanout}). Upstream runs the same alarm inline here, before the save;
     * this fork keeps it behind the event. {@code @Transactional} is required for that - an
     * {@code AFTER_COMMIT} listener does not fire without a transaction to commit - and it is
     * also what makes the alarm react to a reading that exists rather than one about to.
     */
    @Transactional
    public Reading create(Reading readingReq, User user) {
        if (!user.getCompany().getSubscription().getSubscriptionPlan().getFeatures().contains(PlanFeatures.METER))
            throw new CustomException("Access denied", HttpStatus.FORBIDDEN);
        Optional<Meter> optionalMeter = meterService.findById(readingReq.getMeter().getId());
        if (optionalMeter.isPresent()) {
            Meter meter = optionalMeter.get();
            if (!meter.canBeViewedBy(user))
                throw new CustomException("Access denied", HttpStatus.FORBIDDEN);
            Optional<Reading> optionalLastReading = findLastByMeter(readingReq.getMeter().getId());
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
            Reading savedReading = readingRepository.save(readingReq);
            announce(savedReading);
            return savedReading;
        } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);
    }

    public Collection<Reading> getAll() {
        return readingRepository.findAll();
    }

    public Collection<Reading> getByMeter(Long id, User user) {
        Optional<Meter> optionalMeter = meterService.findById(id);
        if (optionalMeter.isPresent()) {
            if (!optionalMeter.get().canBeViewedBy(user))
                throw new CustomException("Access denied", HttpStatus.FORBIDDEN);
            return readingRepository.findByMeter_Id(id);
        } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);
    }

    public List<ReadingHistogramDTO> getHistogram(Long id, DateRange dateRange, User user) {
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
        return getHistogramData(id, dateRange.getStart(),
                dateRange.getEnd(), user.getCompany().getCompanySettings().getGeneralPreferences().getTimeZone());
    }

    @Transactional
    public Reading patch(Long id, ReadingPatchDTO reading, User user) {
        Optional<Reading> optionalReading = readingRepository.findById(id);

        if (optionalReading.isPresent()) {
            Reading savedReading = optionalReading.get();
            if (!savedReading.getMeter().canBeViewedBy(user))
                throw new CustomException("Access denied", HttpStatus.FORBIDDEN);
            Reading updated = readingRepository.save(readingMapper.updateReading(savedReading, reading));
            announce(updated);
            return updated;
        } else throw new CustomException("Reading not found", HttpStatus.NOT_FOUND);
    }

    @Transactional
    public void deleteByIdAndUser(Long id, User user) {
        Optional<Reading> optionalReading = readingRepository.findById(id);

        if (optionalReading.isPresent()) {
            if (!optionalReading.get().getMeter().canBeViewedBy(user))
                throw new CustomException("Access denied", HttpStatus.FORBIDDEN);
            readingRepository.deleteById(id);
        } else throw new CustomException("Reading not found", HttpStatus.NOT_FOUND);
    }

    public Optional<Reading> findById(Long id) {
        return readingRepository.findById(id);
    }

    public Collection<Reading> findByCompany(Long id) {
        return readingRepository.findByCompany_Id(id);
    }

    public Optional<Reading> findLastByMeter(Long id) {
        return readingRepository.findFirstByMeter_IdOrderByCreatedAtDesc(id);
    }

    /**
     * A corrected reading is announced like a new one, because the trigger check always ran on
     * both. Editing a value up past a threshold therefore still raises the work order it would
     * have raised had the value arrived that way.
     */
    private void announce(Reading reading) {
        if (reading.getMeter() == null || reading.getMeter().getId() == null) {
            return;
        }
        semanticEventPublisher.publishDomainEvent(new ReadingRecorded(reading.getId(),
                reading.getMeter().getId(), reading.getValue(), CurrentActor.userIdOrNull()));
    }

    public List<ReadingHistogramDTO> getHistogramData(Long meterId, Date start, Date end, @NotNull String timeZone) {
        Collection<Reading> readings = readingRepository.findByMeter_IdAndCreatedAtBetween(meterId, start, end);
        if (readings.isEmpty()) {
            return Collections.emptyList();
        }

        long totalDays = TimeUnit.MILLISECONDS.toDays(end.getTime() - start.getTime()) + 1;
        int maxPoints = 30;

        List<Reading> sorted = readings.stream()
                .sorted(Comparator.comparing(Reading::getCreatedAt))
                .toList();

        int bucketSize = (int) Math.max(1, Math.ceil((double) totalDays / maxPoints));

        Calendar cal = Calendar.getInstance();
        cal.setTime(start);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.setTimeZone(TimeZone.getTimeZone(timeZone));

        List<ReadingHistogramDTO> result = new ArrayList<>();
        Date bucketStart = cal.getTime();

        while (!bucketStart.after(end)) {
            cal.setTime(bucketStart);
            cal.add(Calendar.DAY_OF_MONTH, bucketSize);
            cal.add(Calendar.MILLISECOND, -1);
            Date bucketEnd = cal.getTime();
            if (bucketEnd.after(end)) {
                bucketEnd = end;
            }

            final Date bStart = bucketStart;
            final Date bEnd = bucketEnd;
            List<Reading> bucket = sorted.stream()
                    .filter(r -> !r.getCreatedAt().before(bStart) && !r.getCreatedAt().after(bEnd))
                    .toList();

            if (!bucket.isEmpty()) {
                double avg = bucket.stream().mapToDouble(Reading::getValue).average().orElse(0);
                Date midpoint = new Date((bStart.getTime() + bEnd.getTime()) / 2);
                result.add(ReadingHistogramDTO.builder()
                        .date(midpoint)
                        .value(Math.round(avg * 100.0) / 100.0)
                        .count(bucket.size())
                        .build());
            }

            cal.setTime(bucketStart);
            cal.add(Calendar.DAY_OF_MONTH, bucketSize);
            bucketStart = cal.getTime();
        }

        return result;
    }
}