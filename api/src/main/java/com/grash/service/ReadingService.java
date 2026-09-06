package com.grash.service;

import com.grash.automation.event.CurrentActor;
import com.grash.automation.event.SemanticEventPublisher;
import com.grash.dto.ReadingHistogramDTO;
import com.grash.dto.ReadingPatchDTO;
import com.grash.event.fanout.ReadingRecorded;
import com.grash.exception.CustomException;
import com.grash.mapper.ReadingMapper;
import com.grash.model.Reading;
import com.grash.repository.ReadingRepository;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
     * <p>The announcement is what moved the meter alarm out of {@code ReadingController}: the
     * threshold check, the work order it raises, the notification and the webhook are a consumer
     * now ({@code MeterTriggerFanout}). {@code @Transactional} is required for that — an
     * {@code AFTER_COMMIT} listener does not fire without a transaction to commit — and it is
     * also what makes the alarm react to a reading that exists, which the controller could not:
     * it evaluated the triggers <em>before</em> saving.
     */
    @Transactional
    public Reading create(Reading Reading) {
        Reading savedReading = readingRepository.save(Reading);
        announce(savedReading);
        return savedReading;
    }

    @Transactional
    public Reading update(Long id, ReadingPatchDTO reading) {
        if (readingRepository.existsById(id)) {
            Reading savedReading = readingRepository.findById(id).get();
            Reading updatedReading = readingRepository.save(readingMapper.updateReading(savedReading, reading));
            announce(updatedReading);
            return updatedReading;
        } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);
    }

    /**
     * A corrected reading is announced like a new one, because the controller used to run the
     * trigger check on both. Editing a value up past a threshold therefore still raises the work
     * order it would have raised had the value arrived that way.
     */
    private void announce(Reading reading) {
        if (reading.getMeter() == null || reading.getMeter().getId() == null) {
            return;
        }
        semanticEventPublisher.publishDomainEvent(new ReadingRecorded(reading.getId(),
                reading.getMeter().getId(), reading.getValue(), CurrentActor.userIdOrNull()));
    }

    public Collection<Reading> getAll() {
        return readingRepository.findAll();
    }

    public void delete(Long id) {
        readingRepository.deleteById(id);
    }

    public Optional<Reading> findById(Long id) {
        return readingRepository.findById(id);
    }

    public Collection<Reading> findByCompany(Long id) {
        return readingRepository.findByCompany_Id(id);
    }

    public Collection<Reading> findByMeter(Long id) {
        return readingRepository.findByMeter_Id(id);
    }

    public Optional<Reading> findLastByMeter(Long id) {
        return readingRepository.findFirstByMeter_IdOrderByCreatedAtDesc(id);
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
