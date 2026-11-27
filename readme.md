# Locks
This project contains different synchronization ideas (not only exclusive to locks) that
I'd find interesting and want to build.

Currently, it only has `RelayLock`, though with time, I'd definitely include more

## Benchmarks
I've written some JMH benchmarks to compare the performance (in latency) of RelayLock to some baselines. 
To run the benchmarks, you'd need to have maven installed. With that said, you can run the benchmarks as so 
```bash
mvn clean test -Pjmh
```

## Tests
I've also included some JCStress tests. So far, I've only run them on x86 which doesn't tell me too much
but these are nice to have. You can run the stress tests as so
```bash
mvn clean test -Pjcstress "-Djcstress.mode=tough" "-Djcstress.forks=n" "-Djcstress.test=.*TestClass"
```

## License
MIT