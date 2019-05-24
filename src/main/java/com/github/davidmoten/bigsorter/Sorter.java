package com.github.davidmoten.bigsorter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Consumer;

import com.github.davidmoten.guavamini.Preconditions;

public final class Sorter<T> {

    private final InputStream input;
    private final Serializer<T> serializer;
    private final File output;
    private final Comparator<? super T> comparator;
    private final int maxFilesPerMerge;
    private final int maxItemsPerPart;
    private final Consumer<? super String> log;

    Sorter(InputStream input, Serializer<T> serializer, File output, Comparator<? super T> comparator,
            int maxFilesPerMerge, int maxItemsPerFile, Consumer<? super String> log) {
        Preconditions.checkNotNull(input, "input must be specified");
        Preconditions.checkNotNull(serializer, "serializer must be specified");
        Preconditions.checkNotNull(output, "output must be specified");
        Preconditions.checkNotNull(comparator, "comparator must be specified");
        Preconditions.checkArgument(maxFilesPerMerge > 0);
        Preconditions.checkArgument(maxItemsPerFile > 0);
        this.input = input;
        this.serializer = serializer;
        this.output = output;
        this.comparator = comparator;
        this.maxFilesPerMerge = maxFilesPerMerge;
        this.maxItemsPerPart = maxItemsPerFile;
        this.log = log;
    }

    public static <T> Builder<T> serializer(Serializer<T> serializer) {
        return new Builder<T>(serializer);
    }

    public static <T> Builder<String> serializerText(Charset charset) {
        return serializer(Serializer.lines(charset)).comparator(Comparator.naturalOrder());
    }

    public static <T> Builder<String> serializerTextUtf8() {
        return serializer(Serializer.linesUtf8()).comparator(Comparator.naturalOrder());
    }

    public static final class Builder<T> {
        private InputStream input;
        private final Serializer<T> serializer;
        private File output;
        private Comparator<? super T> comparator;
        private int maxFilesPerMerge = 100;
        private int maxItemsPerFile = 100000;
        private File inputFile;
        private Consumer<? super String> logger = null;

        Builder(Serializer<T> serializer) {
            this.serializer = serializer;
        }

        public Builder<T> input(String string, Charset charset) {
            return input(new ByteArrayInputStream(string.getBytes(charset)));
        }

        public Builder<T> input(String string) {
            return input(string, StandardCharsets.UTF_8);
        }

        public Builder<T> input(InputStream input) {
            Preconditions.checkArgument(this.inputFile == null, "cannot specify both InputStream and File as input");
            this.input = input;
            return this;
        }

        public Builder<T> input(File inputFile) {
            Preconditions.checkArgument(this.input == null, "cannot specify both InputStream and File as input");
            this.inputFile = inputFile;
            return this;
        }

        public Builder<T> output(File output) {
            this.output = output;
            return this;
        }

        public Builder<T> comparator(Comparator<? super T> comparator) {
            this.comparator = comparator;
            return this;
        }

        public Builder<T> maxFilesPerMerge(int value) {
            this.maxFilesPerMerge = value;
            return this;
        }

        public Builder<T> maxItemsPerFile(int value) {
            this.maxItemsPerFile = value;
            return this;
        }

        public Builder<T> logger(Consumer<? super String> logger) {
            this.logger = logger;
            return this;
        }

        public Builder<T> loggerStdOut() {
            return logger(new Consumer<String>() {

                @Override
                public void accept(String msg) {
                    System.out.println(
                            ZonedDateTime.now().truncatedTo(ChronoUnit.MILLIS).format(DateTimeFormatter.ISO_DATE_TIME)
                                    + " " + msg);
                }

            });
        }

        /**
         * Sorts the input and writes the result to the given output file. If an
         * {@link IOException} occurs then it is thrown wrapped in
         * {@link UncheckedIOException}.
         */
        public void sort() {
            try {
                if (inputFile != null) {
                    try (InputStream in = new BufferedInputStream(new FileInputStream(inputFile))) {
                        sort(in);
                    }
                } else {
                    sort(input);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private void sort(InputStream input) {
            Sorter<T> sorter = new Sorter<T>(input, serializer, output, comparator, maxFilesPerMerge, maxItemsPerFile,
                    logger);
            try {
                sorter.sort();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private void log(String msg, Object... objects) {
        if (log != null) {
            String s = String
                    .format(ZonedDateTime.now().truncatedTo(ChronoUnit.MILLIS).format(DateTimeFormatter.ISO_DATE_TIME)
                            + " " + msg, objects);
            log.accept(s);
        }
    }

    private File sort() throws IOException {
        // read the input into sorted small files
        long time = System.currentTimeMillis();
        List<File> files = new ArrayList<>();
        log("starting sort");
        try (Reader<T> reader = serializer.createReader(input)) {
            {
                int i = 0;
                List<T> list = new ArrayList<>();
                while (true) {
                    T t = reader.read();
                    if (t != null) {
                        list.add(t);
                        i++;
                    }
                    if (t == null || i == maxItemsPerPart) {
                        i = 0;
                        if (list.size() > 0) {
                            File f = sortAndWriteToFile(list);
                            files.add(f);
                            list.clear();
                        }
                    }
                    if (t == null) {
                        break;
                    }
                }
            }
        }
        log("completed inital split and sort, starting merge");
        // merge the files in chunks repeatededly until only one remains
        while (files.size() > 1) {
            List<File> nextRound = new ArrayList<>();
            for (int i = 0; i < files.size(); i += maxFilesPerMerge) {
                File merged = merge(files.subList(i, Math.min(files.size(), i + maxFilesPerMerge)));
                nextRound.add(merged);
            }
            files = nextRound;
        }
        File result;
        if (files.isEmpty()) {
            output.delete();
            output.createNewFile();
            result = output;
        } else {
            result = files.get(0);
        }
        Files.move( //
                result.toPath(), //
                output.toPath(), //
                StandardCopyOption.ATOMIC_MOVE, //
                StandardCopyOption.REPLACE_EXISTING);
        log("sort completed in " + (System.currentTimeMillis() - time) / 1000.0 + "s");
        return output;
    }

    private File merge(List<File> list) throws IOException {
        log("merging %s ", list.size());
        Preconditions.checkArgument(!list.isEmpty());
        if (list.size() == 1) {
            return list.get(0);
        }
        List<State<T>> states = new ArrayList<>();
        for (File f : list) {
            State<T> st = createState(f);
            if (st.value != null) {
                states.add(st);
            }
        }
        File output = nextTempFile();
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(output));
                Writer<T> writer = serializer.createWriter(out)) {
            PriorityQueue<State<T>> q = new PriorityQueue<>((x, y) -> comparator.compare(x.value, y.value));
            q.addAll(states);
            while (!q.isEmpty()) {
                State<T> state = q.poll();
                writer.write(state.value);
                state.value = state.reader.readAutoClosing();
                if (state.value != null) {
                    q.offer(state);
                } else {
                    // delete intermediate files
                    state.file.delete();
                }
            }
            //TODO if an IOException occurs then we should attempt to close and delete temporary files
        }
        return output;
    }

    private State<T> createState(File f) throws IOException {
        InputStream in = new BufferedInputStream(new FileInputStream(f));
        Reader<T> reader = serializer.createReader(in);
        T t = reader.readAutoClosing();
        return new State<T>(f, reader, t);
    }

    private static final class State<T> {
        final File file;
        Reader<T> reader;
        T value;

        State(File file, Reader<T> reader, T value) {
            this.file = file;
            this.reader = reader;
            this.value = value;
        }
    }

    private File sortAndWriteToFile(List<T> list) throws FileNotFoundException, IOException {
        File file = nextTempFile();
        log("sorting %s records", list.size());
        Collections.sort(list, comparator);
        writeToFile(list, file);
        log("sorted records written to file %s", file.getName());
        return file;
    }

    private void writeToFile(List<T> list, File f) throws FileNotFoundException, IOException {
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(f));
                Writer<T> writer = serializer.createWriter(out)) {
            for (T t : list) {
                writer.write(t);
            }
        }
    }

    private static File nextTempFile() throws IOException {
        return File.createTempFile("big-sorter", "");
    }

}
