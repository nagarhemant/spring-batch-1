package com.springbatch;

import com.springbatch.itemReaders.SimpleItemReader;
import com.springbatch.jobDeciders.DeliveryDecider;
import com.springbatch.jobDeciders.ReceiptDecider;
import com.springbatch.listeners.FlowersSelectionStepExecutionListener;
import com.springbatch.mappers.OrderFieldSetMapper;
import com.springbatch.mappers.OrderRowMapper;
import com.springbatch.models.Order;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.core.job.flow.support.SimpleFlow;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.mapping.DefaultLineMapper;
import org.springframework.batch.item.file.transform.DelimitedLineTokenizer;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import javax.sql.DataSource;
import java.util.List;

@SpringBootApplication
@EnableBatchProcessing
public class SpringBatchApplication {

    public static String[] tokens = new String[] {"order_id", "first_name", "last_name", "email", "cost", "item_id", "item_name", "ship_date"};

    public static String ORDER_SQL = "select order_id, first_name, last_name, "
            + "email, cost, item_id, item_name, ship_date "
            + "from SHIPPED_ORDER order by order_id";

    @Autowired
    public JobBuilderFactory jobBuilderFactory;

    @Autowired
    public StepBuilderFactory stepBuilderFactory;

    @Autowired
    public DataSource dataSource;

    @Bean
    public JobExecutionDecider decider() {
        return new DeliveryDecider();
    }

    @Bean
    public JobExecutionDecider receiptDecider() {
        return new ReceiptDecider();
    }

    @Bean
    public StepExecutionListener selectFlowerListener() {
        return new FlowersSelectionStepExecutionListener();
    }

    @Bean
    public Step nestedBillingJobStep() {
        return this.stepBuilderFactory.get("nestedBillingJobStep").job(billingJob()).build();
    }

    @Bean
    public Step sendInvoiceStep() {
        return this.stepBuilderFactory.get("invoiceStep").tasklet((stepContribution, chunkContext) -> {
            System.out.println("Invoice is sent to the customer.");
            return RepeatStatus.FINISHED;
        }).build();
    }

    @Bean
    public Flow billingFlow() {
        return new FlowBuilder<SimpleFlow>("billingFlow").start(sendInvoiceStep()).build();
    }

    @Bean
    public Job billingJob() {
        return this.jobBuilderFactory.get("billingJob").start(sendInvoiceStep()).build();
    }

    @Bean
    public Flow deliveryFlow() {
        return new FlowBuilder<SimpleFlow>("deliveryFlow").start(driveToAddressStep())
                    .on("FAILED").fail()
                .from(driveToAddressStep())
                    .on("*").to(decider())
                        .on("PRESENT").to(givePackageToCustomerStep())
                            .next(receiptDecider()).on("CORRECT").to(thankCustomerStep())
                            .from(receiptDecider()).on("IN_CORRECT").to(refundStep())
                    .from(decider())
                        .on("NOT_PRESENT").to(leaveAtDoorStep()).build();
    }

    @Bean
    public Step thankCustomerStep() {
        return this.stepBuilderFactory.get("thankCustomerStep").tasklet((stepContribution, chunkContext) -> {
            System.out.println("Thanking the customer.");
            return RepeatStatus.FINISHED;
        }).build();
    }

    @Bean
    public Step refundStep() {
        return this.stepBuilderFactory.get("refundStep").tasklet((stepContribution, chunkContext) -> {
            System.out.println("Refunding customer money.");
            return RepeatStatus.FINISHED;
        }).build();
    }

    @Bean
    public Step leaveAtDoorStep() {
        return this.stepBuilderFactory.get("leaveAtDoorStep").tasklet((stepContribution, chunkContext) -> {
            System.out.println("Leaving the package at the door.");
            return RepeatStatus.FINISHED;
        }).build();
    }

    @Bean
    public Step storePackageStep() {
        return this.stepBuilderFactory.get("storePackageStep").tasklet((stepContribution, chunkContext) -> {
            System.out.println("Storing the package while the customer customer address is located.");
            return RepeatStatus.FINISHED;
        }).build();
    }


    @Bean
    public Step givePackageToCustomerStep() {
        return this.stepBuilderFactory.get("givePackageToCustomerStep").tasklet((stepContribution, chunkContext) -> {
            System.out.println("Given the package to the customer");
            return RepeatStatus.FINISHED;
        }).build();
    }

    @Bean
    public Step driveToAddressStep() {
        boolean GOT_LOST = false;
        return this.stepBuilderFactory.get("driveToAddressStep").tasklet((stepContribution, chunkContext) -> {
            if (GOT_LOST) {
                throw new RuntimeException("Got lost driving to the address!");
            }
            System.out.println("Successfully arrived at the address.");
            return RepeatStatus.FINISHED;
        }).build();
    }

    @Bean
    public Step packageItemStep() {
        return this.stepBuilderFactory.get("packageItemStep")
                .tasklet((stepContribution, chunkContext) -> {
                    String item = chunkContext.getStepContext().getJobParameters().get("item").toString();
                    String date = chunkContext.getStepContext().getJobParameters().get("run.date").toString();
                    System.out.println(String.format("The %s has been packaged on %s.", item, date));
                    return RepeatStatus.FINISHED;
                }).build();
    }

    @Bean
    public Job deliverPackageJob() {
        return this.jobBuilderFactory.get("deliverPackageJob")
                .start(packageItemStep())
                .split(new SimpleAsyncTaskExecutor())
                .add(deliveryFlow(), billingFlow())
                .end()
                .build();
    }

    @Bean
    public Step selectFlowersStep() {
        return this.stepBuilderFactory.get("selectFlowersStep").tasklet((stepContribution, chunkContext) -> {
            System.out.println("Gathering flowers for order.");
            return RepeatStatus.FINISHED;
        }).listener(selectFlowerListener()).build();
    }

    @Bean
    public Step removeThornsStep() {
        return this.stepBuilderFactory.get("removeThornsStep").tasklet((stepContribution, chunkContext) -> {
            System.out.println("Remove thorns from roses.");
            return RepeatStatus.FINISHED;
        }).build();
    }

    @Bean
    public Step arrangeFlowersStep() {
        return this.stepBuilderFactory.get("arrangeFlowersStep").tasklet((stepContribution, chunkContext) -> {
            System.out.println("Arranging flowers for order.");
            return RepeatStatus.FINISHED;
        }).build();
    }

    @Bean
    public Job prepareFlowers() {
        return this.jobBuilderFactory.get("prepareFlowersJob")
                .start(selectFlowersStep())
                    .on("TRIM_REQUIRED").to(removeThornsStep()).next(arrangeFlowersStep())
                .from(selectFlowersStep())
                    .on("NO_TRIM_REQUIRED").to(arrangeFlowersStep())
                .from(arrangeFlowersStep()).on("*").to(deliveryFlow())
                .end()
                .build();
    }

    // FlatFileItemReader
    /*@Bean
    public ItemReader<Order> itemReader() {
        FlatFileItemReader<Order> itemReader = new FlatFileItemReader<Order>();
        // skips first line because it contains the headers as opposed to actual data that we'd like to process.
        itemReader.setLinesToSkip(1);
        itemReader.setResource(new FileSystemResource("shipped_orders.csv"));

        DefaultLineMapper<Order> lineMapper = new DefaultLineMapper<Order>();
        DelimitedLineTokenizer tokenizer = new DelimitedLineTokenizer();
        tokenizer.setNames(tokens);

        lineMapper.setLineTokenizer(tokenizer);

        lineMapper.setFieldSetMapper(new OrderFieldSetMapper());

        itemReader.setLineMapper(lineMapper);
        return itemReader;
    }*/

    @Bean
    public ItemReader<Order> itemReader() {
        return new JdbcCursorItemReaderBuilder<Order>()
                .dataSource(dataSource)
                .name("jdbcCursorItemReader")
                .sql(ORDER_SQL)
                .rowMapper(new OrderRowMapper())
                .build();
    }

    @Bean
    public Step chunkBasedStep() {
        return this.stepBuilderFactory.get("chunkBasedStep")
                .<Order, Order>chunk(3)
                .reader(itemReader())
                .writer(new ItemWriter<Order>() {
                    @Override
                    public void write(List<? extends Order> items) throws Exception {
                        System.out.println(String.format("Received list of size: %s", items.size()));
                        items.forEach(System.out::println);
                    }
                }).build();
    }

    @Bean
    public Job chunkBasedJob() {
        return this.jobBuilderFactory.get("chunkBasedJob")
                .start(chunkBasedStep())
                .build();
    }

    public static void main(String[] args) {
        SpringApplication.run(SpringBatchApplication.class, args);
    }

}
