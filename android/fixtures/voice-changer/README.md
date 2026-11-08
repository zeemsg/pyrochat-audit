# Voice changer: one result on both platforms

`golden.txt` holds, for one fixed input, every fortieth sample of the three voices of the voice
changer (docs/VOICE-CHANGER.md) as both apps must produce them:

```
deep  <300 numbers>     pitch factor 0.77 (a pick of 0.5)
high  <300 numbers>     pitch factor 1.35 (a pick of 0.5)
robot <300 numbers>
```

The input is not a file. Both test suites build it from the same formula: a made-up voice that
glides from 110 to 170 Hz over half a second, with a little noise from a fixed generator, so
nothing in it repeats exactly.

- Android: `VoiceDspTest.bothPlatformsProduceTheSameVoices` (`android/app`, JVM).
- iOS: `VoiceDspTests.testBothPlatformsProduceTheSameVoices`.

Both compare within 0.0002. The two platforms' `sin` and `cos` differ in the last bit, which
is far below that; an arithmetic that differed would be far above it.

The file is written by Android, and only on purpose, after a deliberate change to the
arithmetic:

```
cd android && PYRO_VOICE_GOLDEN=write ./gradlew :app:testDebugUnitTest --tests '*VoiceDspTest*'
```

Then run the iOS test against the new file before committing either.
