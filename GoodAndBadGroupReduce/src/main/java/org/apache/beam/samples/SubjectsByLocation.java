package org.apache.beam.samples;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.options.*;
import org.apache.beam.sdk.transforms.*;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;

/**
 * This class calculates the weight of each subjects in each location. The weigh is equal to the number of events
 * related to this subject in the location
 *
 */

public class SubjectsByLocation {
    private static final Logger LOG = LoggerFactory.getLogger(SubjectsByLocation.class);

    /**
     * Specific pipeline options.
     */
    private interface Options extends PipelineOptions {
        String GDELT_EVENTS_URL = "http://data.gdeltproject.org/events/";

        @Description("GDELT file date")
        @Default.InstanceFactory(GDELTFileFactory.class)
        String getDate();

        void setDate(String value);

        @Description("Input Path")
        String getInput();

        void setInput(String value);

        @Description("Output Path")
        String getOutput();

        void setOutput(String value);

        class GDELTFileFactory implements DefaultValueFactory<String> {
            public String create(PipelineOptions options) {
                SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd");
                return format.format(new Date());
            }
        }
    }

    private static String getCountry(String row) {
        String[] fields = row.split("\\t+");
        if (fields.length > 22) {
            if (fields[21].length() > 2) {
                return fields[21].substring(0, 1);
            }
            return fields[21];
        }
        return "NA";
    }

    private static String getSubject(String row) {
        String[] fields = row.split("\\t+");
        if (fields[6].length() > 0)
            return fields[6];
        return "NA";
    }

    private static String getCompositeKey(String row) {
        StringBuilder compositeKey = new StringBuilder();
        //TODO refactor, we tokenize twice
        String country = getCountry(row);
        String subject = getSubject(row);
        if (!"NA".equals(country) && country.length() == 2 && !country.startsWith("-") && !"NA".equals(subject)) {
            compositeKey.append(country).append("_").append(subject);
            return (compositeKey.toString());
        }
        return "NA";
    }

    private static class subjectsByLocationTransformGood extends PTransform<PCollection<String>, PCollection<String>> {

        @Override
        public PCollection<String> apply(PCollection<String> inputCollection) {

            PCollection<String> compositeKeys =
                    inputCollection.apply("extractCompositeKey", ParDo.of(new DoFn<String, String>() {
                        @ProcessElement
                        public void processElement(ProcessContext c) {
                            c.output(getCompositeKey(c.element()));
                        }
                    })).apply("FilterValidCompositeKeys", Filter.by(new SerializableFunction<String, Boolean>() {
                        public Boolean apply(String input) {
                            return (!input.equals("NA"));
                        }
                    }));
            PCollection<KV<String, Long>> compositesEventsPairs =
                    compositeKeys.apply("eventsByCompositeKey", Count.<String>perElement());

            PCollection<String> result = compositesEventsPairs.apply("formatOutput", MapElements.via(
                    new SimpleFunction<KV<String, Long>, String>() {
                        @Override
                        public String apply(KV<String, Long> kv) {
                            StringBuilder str = new StringBuilder();
                            String[] split = kv.getKey().split("_");
                            String country = split[0];
                            String subject = split[1];
                            Long eventsNb = kv.getValue();
                            str.append(country).append(" ").append(subject).append(" ").append(eventsNb);
                            return str.toString();
                        }
                    }));

            return result;
        }

    }

    private static class subjectsByLocationTransformBad extends PTransform<PCollection<String>, PCollection<String>> {

        @Override
        public PCollection<String> apply(PCollection<String> inputCollection) {

            PCollection<KV<String, String>> countriesSubjectsPairs =
                    inputCollection.apply("extractCountrySubjectPairs",
                                          MapElements.via(new SimpleFunction<String, KV<String, String>>() {
                                              @Override
                                              public KV<String, String> apply(String s) {
                                                  return KV.of(getCountry(s), getSubject(s));
                                              }
                                          }));

            //group subjects by country => bad because it shuffles subject data to group them by country (bandwidth use + slowing pipeline).
            // And if a country has many events in the dataset, a given worker will end up
            // having all subject data in memory for that country. Might lead to an out of memory on the worker.

            PCollection<KV<String, Iterable<String>>> subjectsByCountry =
                    countriesSubjectsPairs.apply("groupSubjectsByCountry", GroupByKey.<String, String>create());

            PCollection<KV<String, HashMap<String, Long>>> countriesConcernsPairs =
                    subjectsByCountry.apply("eventsBySubjects", MapElements.via(
                            new SimpleFunction<KV<String, Iterable<String>>, KV<String, HashMap<String, Long>>>() {
                                @Override
                                public KV<String, HashMap<String, Long>> apply(KV<String, Iterable<String>> kv) {
                                    HashMap<String, Long> eventsBySubjects = new HashMap();
                                    for (String subject : kv.getValue()) {
                                        Long nbOfEvents = eventsBySubjects.get(subject);
                                        eventsBySubjects.put(subject, nbOfEvents++);
                                    }
                                    return KV.of(kv.getKey(), eventsBySubjects);
                                }
                            }));
            PCollection<String> result = countriesConcernsPairs.apply("formatOutput", MapElements.via(
                    new SimpleFunction<KV<String, HashMap<String, Long>>, String>() {
                        @Override
                        public String apply(KV<String, HashMap<String, Long>> kv) {
                            StringBuilder str = new StringBuilder();
                            String country = kv.getKey();
                            str.append(country).append(" ");
                            HashMap<String, Long> concerns = kv.getValue();
                            for (String subject : concerns.keySet()) {
                                str.append(subject);
                                str.append(" ");
                                Long eventsNb = concerns.get(subject);
                                str.append(eventsNb);
                            }
                            return str.toString();
                        }
                    }));

            return result;

        }

    }

    public static void main(String[] args) throws Exception {
        Options options = PipelineOptionsFactory.fromArgs(args).withValidation().as(Options.class);
        if (options.getInput() == null) {
            options.setInput(Options.GDELT_EVENTS_URL + options.getDate() + ".export.CSV.zip");
        }
        if (options.getOutput() == null) {
            options.setOutput("/tmp/gdelt-" + options.getDate());
        }
        LOG.info("Common options: " + options.toString());

        Pipeline goodPipeline = Pipeline.create(options);
        goodPipeline.apply("ReadFromGDELTFile", TextIO.Read.from(options.getInput()))
                .apply("TakeASample", Sample.<String>any(10000))
                .apply("subjectsByLocation", new subjectsByLocationTransformGood())
                .apply("WriteResults", TextIO.Write.to(options.getOutput() + "good/"));
        Instant start = Instant.now();
        goodPipeline.run();
        Instant end = Instant.now();
        long runningTimeForGoodPipeline = end.getMillis() - start.getMillis();

/*
        Pipeline badPipeline = Pipeline.create(options);
        badPipeline.apply("ReadFromGDELTFile", TextIO.Read.from(options.getInput()))
                .apply("TakeASample", Sample.<String>any(10000))
                .apply("subjectsByLocation", new subjectsByLocationTransformBad())
                .apply("WriteResults", TextIO.Write.to(options.getOutput() + "bad/"));

        start = Instant.now();
        badPipeline.run();
        end = Instant.now();
        long runningTimeForBadPipeline = end.getMillis() - start.getMillis();
*/

        LOG.info("Good pipeline runs in " + String.valueOf(runningTimeForGoodPipeline) + " ms");
/*
        LOG.info("Bad pipeline runs in " + String.valueOf(runningTimeForBadPipeline) + " ms");
        LOG.info("Bad pipeline (with groupBy) is slower of " + String.valueOf(
                runningTimeForBadPipeline - runningTimeForGoodPipeline) + " ms");
*/

    }

}