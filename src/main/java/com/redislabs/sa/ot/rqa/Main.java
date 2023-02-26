package com.redislabs.sa.ot.rqa;

import com.google.gson.Gson;
import org.json.JSONObject;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.json.Path;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

/**
 * To run the program loading questions from the local jsonkeyvalue.tldf file (supplying the host and port for Redis) do:
 * mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="--host myhost.com --port 10000"
 *
 * To run the program without loading the questions from the local file (reuse - what is already in Redis) do:
 * mvn compile exec:java -Dexec.cleanupDaemonThreads=false -Dexec.args="--host myhost.com --port 10000 --loadquestions false"
 *
 * This application uses a command-line interface to allow users to take a quiz
 * The data structure used to hold onto the questions and answers will be JSON
 * A separate Set will also be maintained that will allow for random questions to be fetched
 * The set will contain the keynames of the JSON objects and will be populated by using the SCAN command
 * It is assumed that there will only be hundreds of questions and therefore performing the scan and
 * Set population will be fast and doable as each execution of the program and quiz starts
 * A second Set will be used to check for questions already asked and correctly answered during the session
 * ^ this will allow for no duplicate questions being asked during the session
 * As each question is being selected from the known question Set, a new: untouched Set of questions will be used
 * to execute SRANDOM and fetch a random question/answer object
 * types of Questions/Answers supported:
 * 1. Multiple choice (T/F and option 1, 2, 3 etc)
 * 2. TBD (maybe use RedisSearch with Vector Similarity to allow semantic meaning matches?)
 * See --> https://antonum-redis-vss-streamlit-streamlit-app-p4z5th.streamlit.app/
 * for an example of using VSS
 *
 * JSON format for questions might be:
 * json.set q1 $ '{"questionID":"1","questionContent":"what is your favorite color",
                  "answerOptions":[{"1":"blue"},{"2":"yellow"},{"3":"red"}],
                   "correctAnswer":"1",
                   "extraInfo":"No one chooses Yellow and Red is too aggressive!"
                  }'
 *
 * Such questions can be read from a tilde-delimited file (found under /resources)
 * NB: this program does a flimsy check for matching your answer to the 'correctAnswer'
 * it uses:               if(correctAnswer.contains(yourAnswer)){}
 * This means you can not have more than 10 possible options allowed for any one question
 * "1" would match "1", "10", and "21" <-- so only provide a maximum of 10 options for any one question
 *
 */
public class Main {
    static boolean debugJSON=false;
    static boolean doAddQuestions=false;
    static boolean doLoadQuestions=true;
    static JedisPooled jedisPooled = null;
    static Pipeline jedisPipeline = null;
    static int maxConnections = 2;
    static JedisConnectionHelper connectionHelper = null;
    static String uid = "uid"+Runtime.getRuntime().hashCode()+System.nanoTime();
    static String filepath = "src/main/resources/jsonkeyvalue.tldf";
    static int pipeBatchSize = 200;
    static String knownQuestionKeys="knownQuestionKeys";
    static String sessionQuestionKeysRemaining="sessionQuestionKeysRemaining";
    static String questionKeysAsked = "questionKeysAsked";
    static String jsonQuestionKeyPrefix="json:rqa:";
    static final BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

    public static void main(String[] args) {
        ArrayList<String> argList = null;
        String host = "localhost";
        int port = 6379;
        String userName = "default";
        String password = "";
        if (args.length > 0) {
            argList = new ArrayList<>(Arrays.asList(args));
            if (argList.contains("--username")) {
                int argIndex = argList.indexOf("--username");
                userName = argList.get(argIndex + 1);
            }
            if (argList.contains("--password")) {
                int argIndex = argList.indexOf("--password");
                password = argList.get(argIndex + 1);
            }
            if (argList.contains("--host")) {
                int argIndex = argList.indexOf("--host");
                host = argList.get(argIndex + 1);
            }
            if (argList.contains("--port")) {
                int argIndex = argList.indexOf("--port");
                port = Integer.parseInt(argList.get(argIndex + 1));
            }
            if (argList.contains("--debugjson")) {
                int argIndex = argList.indexOf("--debugjson");
                debugJSON = Boolean.parseBoolean(argList.get(argIndex + 1));
            }
            if (argList.contains("--addquestions")) {
                int argIndex = argList.indexOf("--addquestions");
                doAddQuestions = Boolean.parseBoolean(argList.get(argIndex + 1));
            }
            if (argList.contains("--loadquestions")) {
                int argIndex = argList.indexOf("--loadquestions");
                doLoadQuestions = Boolean.parseBoolean(argList.get(argIndex + 1));
            }if (argList.contains("--filepath")) {
                int index = argList.indexOf("--filepath");
                filepath = argList.get(index + 1);
            }
            if (argList.contains("--pipebatchsize")) {
                int index = argList.indexOf("--pipebatchsize");
                pipeBatchSize = Integer.parseInt(argList.get(index + 1));
            }
        }
        connectionHelper = new JedisConnectionHelper(host, port, userName, password, maxConnections);
        jedisPooled = connectionHelper.getPooledJedis();
        jedisPipeline = connectionHelper.getPipeline();
        System.out.println("This is this session's UID: "+uid);
        if(doLoadQuestions) {
            try {
                System.out.println("LOADING QUESTIONS FROM FILE: " + filepath);
                loadJSONDataFromFile(filepath);
            } catch (Throwable t) {
                t.printStackTrace();
                System.exit(0);
            }
        }
        while(true){
            menu();
        }
    }

    static int loadJSONDataFromFile(String path) throws Throwable{
        BufferedReader reader = new BufferedReader(Files.newBufferedReader(Paths.get(path)));
        Gson gson = new Gson();
        int pipeCounter =0;
        String s = null;
        do {
            s = reader.readLine();
            if(null!=s && s.length()>1) {
                String[] lineRead = s.split("~");
                if ((pipeCounter % 1000 == 0)||(debugJSON==true)) {
                    System.out.println("SAMPLE QUESTION key with JSON value follows [DEBUG INFO]...");
                    System.out.println("key == " + lineRead[0]);//# DEBUG
                    System.out.println("json == " + lineRead[1]+"\n\n");//# DEBUG
                }
                String json = lineRead[1];
                Map<?, ?> map = gson.fromJson(json, Map.class);
                JSONObject obj = new JSONObject(map);
                jedisPipeline.jsonSet(jsonQuestionKeyPrefix+lineRead[0], obj);
                pipeCounter++;
                if (pipeCounter % 1000 == 0) {
                    System.out.println("obj added to pipeline...\n" + obj); //# DEBUG
                }
                if (pipeCounter % pipeBatchSize == 0) {
                    jedisPipeline.sync();
                }
            }
        }while(null!=s);
        jedisPipeline.sync();//in case there are extra objects in the pipe
        return pipeCounter;
    }

    //remember to use jsonQuestionKeyPrefix when adding new Qs
    //TODO: actually implement this method ( currently, Questions are best added via the jsonkeyvalue.tldf file )
    static void addQuestions(){
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(System.in));
            System.out.println("How many questions do you want to add to your quiz now? [enter a number] ");
            int howManyTimes = Integer.parseInt(reader.readLine());
            for (int x = 0; x < howManyTimes; x++) {
            }
        }catch(Throwable t){System.out.println("\nhmmm... "+t.getMessage());}
    }

    //Note that this implementation is very inefficient in that it makes separate calls to Redis for each JSON element
    // it would be more efficient to
    static void takeQuiz(){
        //The following behavior is executed for all instances of this program (each session)
        //first scan for all possible questions and store them in a Set called [uid]:knownQuestionSet
        ScanParams scanParams = new ScanParams().count(100000).match(jsonQuestionKeyPrefix+"*");
        ScanResult<String> stringScanResult = jedisPooled.scan("0",scanParams);
        int totalNumberAvailableQuestions = stringScanResult.getResult().size();
        for(int x = 0;x<totalNumberAvailableQuestions;x++){
            jedisPipeline.sadd(uid+knownQuestionKeys,stringScanResult.getResult().get(x));
        }
        jedisPipeline.sync();
        int howManyTimes = 1000000000;//Seting this ridiculously high to force at least one execution of the ask for #Questions
        try {
            while(howManyTimes>totalNumberAvailableQuestions) {
                System.out.println("There are "+totalNumberAvailableQuestions+" total questions available");
                System.out.println("How many questions do you want in your quiz? [enter a number] ");
                howManyTimes = Integer.parseInt(getUserInput(""));
                System.out.println("\nSTARTING QUIZ:\n\n");
            }
            for (int x = 0; x < howManyTimes; x++) {
                String input = null;
                System.out.println("\t\t***********************\n\t\tHit Enter to continue...");
                input = getUserInput("");
                System.out.println("\n\n** When answering: Enter the number corresponding to the most correct of the provided possible answers **");
                System.out.println("\n\t\t***********************\n" +
                        "\t\tHere is your question\n" +
                        "\t\t***********************\n");
                jedisPooled.sdiffstore(uid+sessionQuestionKeysRemaining,uid+knownQuestionKeys,uid+questionKeysAsked);
                String questionKey = jedisPooled.srandmember(uid+"sessionQuestionKeysRemaining");
                jedisPooled.sadd(uid+"questionKeysAsked",questionKey);
                jedisPooled.del(uid+sessionQuestionKeysRemaining);
                String questionID = jedisPooled.jsonGet(questionKey,new Path("$.questionID")).toString();
                String question = jedisPooled.jsonGet(questionKey,new Path("$.questionContent")).toString();
                System.out.println("QuestionID="+questionID+"\n\n\t"+question);
                String answerPossibilitiesString = jedisPooled.jsonGet(questionKey,new Path("$.answerOptions")).toString();
                System.out.println("\n"+answerPossibilitiesString);
                String correctAnswer = jedisPooled.jsonGet(questionKey,new Path("$.correctAnswer")).toString();

                input = getUserInput("");
                System.out.println("\t\t***********************\n\t\tyou entered "+input);
                if(correctAnswer.contains(input)){
                    System.out.println("\t\t***********************\n\t\tHuzzah! THAT IS CORRECT!\n");
                    jedisPooled.hincrBy(uid+"quizScores","correctAnswerCount",1);
                }else{
                    System.out.println("Sorry - that is not correct...\n"+correctAnswer+"\n");
                    jedisPooled.hincrBy(uid+"quizScores","wrongAnswerCount",1);
                }
                System.out.println(""+jedisPooled.jsonGet(questionKey,new Path("$.extraInfo")).toString()+"\n");
            }
        }catch(Throwable t){
            System.out.println("\nhmmm... "+t.getMessage());
        }
    }

    static void menu(){
        int choice=0;
        try {
            System.out.println("Here are your options: \n[1] take a quiz\n[2] exit\n");
            System.out.println("Enter your choice as a number: ");
            choice = Integer.parseInt(getUserInput(""));
            if(choice==1){
                jedisPooled.hincrBy(uid+"quizScores","numberOfQuizzesForThisSession",1);
                takeQuiz();
            }else if(choice==2){
                int incorrectCount=Integer.parseInt(jedisPooled.hget(uid+"quizScores","wrongAnswerCount"));
                int correctCount=Integer.parseInt(jedisPooled.hget(uid+"quizScores","correctAnswerCount"));
                float percentageCorrect = ((float)correctCount/((float)correctCount+incorrectCount))*100;
                System.out.println("\nThank you for using the RedisQuizApp!\n" +
                        "During this session you attempted a total of "+
                                jedisPooled.hget(uid+"quizScores","numberOfQuizzesForThisSession")+
                        " quizzes and answered "+ incorrectCount +
                        " Questions incorrectly and answered " + correctCount +" Questions correctly");
                System.out.println("Your score is: "+percentageCorrect+"%");

                System.exit(0);
            }else{
                System.out.println("You entered: "+choice+" That is not a valid option - exiting program...");
                System.exit(0);
            }
        }catch(Throwable t){
            System.out.println("\nhmmm... in menu()..."+t.getMessage()+"  "+t.getClass());
            if(t instanceof java.lang.NumberFormatException ){
                System.out.println("That is not a valid option. Please enter a number next time - exiting program...");
            }
            System.exit(1);
        }
    }

    static String getUserInput(String prompt){
        String userInput = "";
        try{
            System.out.println(prompt);
            userInput= reader.readLine();
        }catch(IOException ioe){
            ioe.printStackTrace();
        }
        return userInput;
    }

}

