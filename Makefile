clean:
	./sbt clean

source-to-image: clean
	./sbt buildversion compile package