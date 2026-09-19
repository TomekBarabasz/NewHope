// Generator : SpinalHDL v1.12.3    git head : 591e64062329e5e2e2b81f4d52422948053edb97
// Component : VgaFbDemoTop
// Git hash  : f728c8f090046c472e924a54cf7386b4f32e9940

`timescale 1ns/1ps

module VgaFbDemoTop (
  input  wire          c3_sys_clk,
  input  wire          c3_sys_rst_n,
  inout  wire [15:0]   mcb3_dram_dq,
  output wire [12:0]   mcb3_dram_a,
  output wire [1:0]    mcb3_dram_ba,
  output wire          mcb3_dram_ras_n,
  output wire          mcb3_dram_cas_n,
  output wire          mcb3_dram_we_n,
  output wire          mcb3_dram_cke,
  output wire          mcb3_dram_ck,
  output wire          mcb3_dram_ck_n,
  inout  wire          mcb3_dram_dqs,
  inout  wire          mcb3_dram_udqs,
  output wire          mcb3_dram_dm,
  output wire          mcb3_dram_udm,
  inout  wire          mcb3_rzq,
  output wire          HSync,
  output wire          VSync,
  output wire [2:0]    Red,
  output wire [2:0]    Green,
  output wire [1:0]    Blue,
  input  wire          UART_RX,
  output wire          UART_TX,
  output wire [7:0]    seg,
  output wire [2:0]    en,
  output wire          calib_done,
  output wire          test_done,
  output wire          test_error,
  output wire          data_error,
  output wire          mcb_fault
);

  wire       [5:0]    mcb_mcb_c3_p0_cmd_bl;
  wire       [29:0]   mcb_mcb_c3_p0_cmd_byte_addr;
  wire                core_vga_io_softReset;
  wire       [29:0]   core_reader_io_base;
  wire                core_loader_io_enable;
  wire       [3:0]    core_status_io_faults;
  wire       [12:0]   mcb_mcb_mcb3_dram_a;
  wire       [1:0]    mcb_mcb_mcb3_dram_ba;
  wire                mcb_mcb_mcb3_dram_ras_n;
  wire                mcb_mcb_mcb3_dram_cas_n;
  wire                mcb_mcb_mcb3_dram_we_n;
  wire                mcb_mcb_mcb3_dram_cke;
  wire                mcb_mcb_mcb3_dram_ck;
  wire                mcb_mcb_mcb3_dram_ck_n;
  wire                mcb_mcb_mcb3_dram_dm;
  wire                mcb_mcb_mcb3_dram_udm;
  wire                mcb_mcb_c3_clk0;
  wire                mcb_mcb_c3_rst0;
  wire                mcb_mcb_c3_calib_done;
  wire                mcb_mcb_c3_p0_cmd_empty;
  wire                mcb_mcb_c3_p0_cmd_full;
  wire                mcb_mcb_c3_p0_wr_full;
  wire                mcb_mcb_c3_p0_wr_empty;
  wire       [6:0]    mcb_mcb_c3_p0_wr_count;
  wire                mcb_mcb_c3_p0_wr_underrun;
  wire                mcb_mcb_c3_p0_wr_error;
  wire       [127:0]  mcb_mcb_c3_p0_rd_data;
  wire                mcb_mcb_c3_p0_rd_full;
  wire                mcb_mcb_c3_p0_rd_empty;
  wire       [6:0]    mcb_mcb_c3_p0_rd_count;
  wire                mcb_mcb_c3_p0_rd_overflow;
  wire                mcb_mcb_c3_p0_rd_error;
  wire                c3_calib_done_buffercc_io_dataOut;
  wire                core_vga_io_frameStart;
  wire                core_vga_io_pixels_ready;
  wire                core_vga_io_vga_vSync;
  wire                core_vga_io_vga_hSync;
  wire                core_vga_io_vga_colorEn;
  wire       [2:0]    core_vga_io_vga_color_r;
  wire       [2:0]    core_vga_io_vga_color_g;
  wire       [1:0]    core_vga_io_vga_color_b;
  wire                core_vga_io_error;
  wire                core_reader_io_bus_cmd_valid;
  wire                core_reader_io_bus_cmd_payload_write;
  wire       [29:0]   core_reader_io_bus_cmd_payload_addr;
  wire       [5:0]    core_reader_io_bus_cmd_payload_bl;
  wire                core_reader_io_bus_wr_valid;
  wire       [127:0]  core_reader_io_bus_wr_payload;
  wire                core_reader_io_bus_rd_ready;
  wire                core_reader_io_pixels_valid;
  wire       [2:0]    core_reader_io_pixels_payload_r;
  wire       [2:0]    core_reader_io_pixels_payload_g;
  wire       [1:0]    core_reader_io_pixels_payload_b;
  wire                core_reader_io_underflow;
  wire                core_painter_io_bus_cmd_valid;
  wire                core_painter_io_bus_cmd_payload_write;
  wire       [29:0]   core_painter_io_bus_cmd_payload_addr;
  wire       [5:0]    core_painter_io_bus_cmd_payload_bl;
  wire                core_painter_io_bus_wr_valid;
  wire       [127:0]  core_painter_io_bus_wr_payload;
  wire                core_painter_io_bus_rd_ready;
  wire                core_painter_io_busy;
  wire                core_loader_io_uart_txd;
  wire                core_loader_io_bus_cmd_valid;
  wire                core_loader_io_bus_cmd_payload_write;
  wire       [29:0]   core_loader_io_bus_cmd_payload_addr;
  wire       [5:0]    core_loader_io_bus_cmd_payload_bl;
  wire                core_loader_io_bus_wr_valid;
  wire       [127:0]  core_loader_io_bus_wr_payload;
  wire                core_loader_io_bus_rd_ready;
  wire                core_loader_io_show;
  wire                core_loader_io_busy;
  wire                core_loader_io_bytePulse;
  wire                core_loader_io_ok;
  wire                core_loader_io_error;
  wire                core_arb_io_read_cmd_ready;
  wire                core_arb_io_read_wr_ready;
  wire                core_arb_io_read_rd_valid;
  wire       [127:0]  core_arb_io_read_rd_payload;
  wire                core_arb_io_paint_cmd_ready;
  wire                core_arb_io_paint_wr_ready;
  wire                core_arb_io_paint_rd_valid;
  wire       [127:0]  core_arb_io_paint_rd_payload;
  wire                core_arb_io_host_cmd_ready;
  wire                core_arb_io_host_wr_ready;
  wire                core_arb_io_host_rd_valid;
  wire       [127:0]  core_arb_io_host_rd_payload;
  wire                core_arb_io_mem_cmd_valid;
  wire                core_arb_io_mem_cmd_payload_write;
  wire       [29:0]   core_arb_io_mem_cmd_payload_addr;
  wire       [5:0]    core_arb_io_mem_cmd_payload_bl;
  wire                core_arb_io_mem_wr_valid;
  wire       [127:0]  core_arb_io_mem_wr_payload;
  wire                core_arb_io_mem_rd_ready;
  wire       [3:0]    core_status_io_digits_0_code;
  wire                core_status_io_digits_0_dot;
  wire       [3:0]    core_status_io_digits_1_code;
  wire                core_status_io_digits_1_dot;
  wire       [3:0]    core_status_io_digits_2_code;
  wire                core_status_io_digits_2_dot;
  wire       [7:0]    core_ss_io_seg;
  wire       [2:0]    core_ss_io_en;
  wire                mcb_port_cmd_valid;
  wire                mcb_port_cmd_ready;
  wire       [2:0]    mcb_port_cmd_payload_instr;
  wire       [5:0]    mcb_port_cmd_payload_bl;
  wire       [29:0]   mcb_port_cmd_payload_addr;
  wire                mcb_port_wr_valid;
  wire                mcb_port_wr_ready;
  wire       [127:0]  mcb_port_wr_payload_data;
  wire       [15:0]   mcb_port_wr_payload_mask;
  wire                mcb_port_rd_valid;
  wire                mcb_port_rd_ready;
  wire       [127:0]  mcb_port_rd_payload;
  wire                mcb_port_cmd_fire;
  wire                mcb_port_wr_fire;
  wire                mcb_port_rd_fire;
  wire                core_calib;
  reg                 core_calib_regNext;
  wire                core_calibRise;
  reg                 core_showB;
  reg                 core_okLatch;
  reg                 core_errLatch;
  reg                 core_underLatch;
  wire                when_VgaFbDemoTop_l143;
  reg                 core_faultLatch;
  wire                when_VgaFbDemoTop_l144;

  s6_lpddr #(
    .C3_P0_MASK_SIZE       (16               ),
    .C3_P0_DATA_PORT_SIZE  (128              ),
    .DEBUG_EN              (0                ),
    .C3_MEMCLK_PERIOD      (10000            ),
    .C3_CALIB_SOFT_IP      ("TRUE"           ),
    .C3_SIMULATION         ("FALSE"          ),
    .C3_RST_ACT_LOW        (0                ),
    .C3_INPUT_CLK_TYPE     ("SINGLE_ENDED"   ),
    .C3_MEM_ADDR_ORDER     ("ROW_BANK_COLUMN"),
    .C3_NUM_DQ_PINS        (16               ),
    .C3_MEM_ADDR_WIDTH     (13               ),
    .C3_MEM_BANKADDR_WIDTH (2                )
  ) mcb_mcb (
    .c3_sys_clk          (c3_sys_clk                       ), //i
    .c3_sys_rst_n        (c3_sys_rst_n                     ), //i
    .mcb3_dram_dq        (mcb3_dram_dq                     ), //~
    .mcb3_dram_a         (mcb_mcb_mcb3_dram_a[12:0]        ), //o
    .mcb3_dram_ba        (mcb_mcb_mcb3_dram_ba[1:0]        ), //o
    .mcb3_dram_ras_n     (mcb_mcb_mcb3_dram_ras_n          ), //o
    .mcb3_dram_cas_n     (mcb_mcb_mcb3_dram_cas_n          ), //o
    .mcb3_dram_we_n      (mcb_mcb_mcb3_dram_we_n           ), //o
    .mcb3_dram_cke       (mcb_mcb_mcb3_dram_cke            ), //o
    .mcb3_dram_ck        (mcb_mcb_mcb3_dram_ck             ), //o
    .mcb3_dram_ck_n      (mcb_mcb_mcb3_dram_ck_n           ), //o
    .mcb3_dram_dqs       (mcb3_dram_dqs                    ), //~
    .mcb3_dram_udqs      (mcb3_dram_udqs                   ), //~
    .mcb3_dram_dm        (mcb_mcb_mcb3_dram_dm             ), //o
    .mcb3_dram_udm       (mcb_mcb_mcb3_dram_udm            ), //o
    .mcb3_rzq            (mcb3_rzq                         ), //~
    .c3_clk0             (mcb_mcb_c3_clk0                  ), //o
    .c3_rst0             (mcb_mcb_c3_rst0                  ), //o
    .c3_calib_done       (mcb_mcb_c3_calib_done            ), //o
    .c3_p0_cmd_clk       (mcb_mcb_c3_clk0                  ), //i
    .c3_p0_cmd_en        (mcb_port_cmd_fire                ), //i
    .c3_p0_cmd_instr     (mcb_port_cmd_payload_instr[2:0]  ), //i
    .c3_p0_cmd_bl        (mcb_mcb_c3_p0_cmd_bl[5:0]        ), //i
    .c3_p0_cmd_byte_addr (mcb_mcb_c3_p0_cmd_byte_addr[29:0]), //i
    .c3_p0_cmd_empty     (mcb_mcb_c3_p0_cmd_empty          ), //o
    .c3_p0_cmd_full      (mcb_mcb_c3_p0_cmd_full           ), //o
    .c3_p0_wr_clk        (mcb_mcb_c3_clk0                  ), //i
    .c3_p0_wr_en         (mcb_port_wr_fire                 ), //i
    .c3_p0_wr_mask       (mcb_port_wr_payload_mask[15:0]   ), //i
    .c3_p0_wr_data       (mcb_port_wr_payload_data[127:0]  ), //i
    .c3_p0_wr_full       (mcb_mcb_c3_p0_wr_full            ), //o
    .c3_p0_wr_empty      (mcb_mcb_c3_p0_wr_empty           ), //o
    .c3_p0_wr_count      (mcb_mcb_c3_p0_wr_count[6:0]      ), //o
    .c3_p0_wr_underrun   (mcb_mcb_c3_p0_wr_underrun        ), //o
    .c3_p0_wr_error      (mcb_mcb_c3_p0_wr_error           ), //o
    .c3_p0_rd_clk        (mcb_mcb_c3_clk0                  ), //i
    .c3_p0_rd_en         (mcb_port_rd_fire                 ), //i
    .c3_p0_rd_data       (mcb_mcb_c3_p0_rd_data[127:0]     ), //o
    .c3_p0_rd_full       (mcb_mcb_c3_p0_rd_full            ), //o
    .c3_p0_rd_empty      (mcb_mcb_c3_p0_rd_empty           ), //o
    .c3_p0_rd_count      (mcb_mcb_c3_p0_rd_count[6:0]      ), //o
    .c3_p0_rd_overflow   (mcb_mcb_c3_p0_rd_overflow        ), //o
    .c3_p0_rd_error      (mcb_mcb_c3_p0_rd_error           )  //o
  );
  (* keep_hierarchy = "TRUE" *) BufferCC_1 c3_calib_done_buffercc (
    .io_dataIn  (mcb_mcb_c3_calib_done            ), //i
    .io_dataOut (c3_calib_done_buffercc_io_dataOut), //o
    .c3_clk0    (mcb_mcb_c3_clk0                  ), //i
    .c3_rst0    (mcb_mcb_c3_rst0                  )  //i
  );
  VgaCtrl core_vga (
    .io_softReset            (core_vga_io_softReset               ), //i
    .io_timings_h_syncStart  (12'h05f                             ), //i
    .io_timings_h_syncEnd    (12'h31f                             ), //i
    .io_timings_h_colorStart (12'h08f                             ), //i
    .io_timings_h_colorEnd   (12'h30f                             ), //i
    .io_timings_h_polarity   (1'b0                                ), //i
    .io_timings_v_syncStart  (12'h001                             ), //i
    .io_timings_v_syncEnd    (12'h20c                             ), //i
    .io_timings_v_colorStart (12'h022                             ), //i
    .io_timings_v_colorEnd   (12'h202                             ), //i
    .io_timings_v_polarity   (1'b0                                ), //i
    .io_frameStart           (core_vga_io_frameStart              ), //o
    .io_pixels_valid         (core_reader_io_pixels_valid         ), //i
    .io_pixels_ready         (core_vga_io_pixels_ready            ), //o
    .io_pixels_payload_r     (core_reader_io_pixels_payload_r[2:0]), //i
    .io_pixels_payload_g     (core_reader_io_pixels_payload_g[2:0]), //i
    .io_pixels_payload_b     (core_reader_io_pixels_payload_b[1:0]), //i
    .io_vga_vSync            (core_vga_io_vga_vSync               ), //o
    .io_vga_hSync            (core_vga_io_vga_hSync               ), //o
    .io_vga_colorEn          (core_vga_io_vga_colorEn             ), //o
    .io_vga_color_r          (core_vga_io_vga_color_r[2:0]        ), //o
    .io_vga_color_g          (core_vga_io_vga_color_g[2:0]        ), //o
    .io_vga_color_b          (core_vga_io_vga_color_b[1:0]        ), //o
    .io_error                (core_vga_io_error                   ), //o
    .c3_clk0                 (mcb_mcb_c3_clk0                     ), //i
    .c3_rst0                 (mcb_mcb_c3_rst0                     )  //i
  );
  FbLineReader core_reader (
    .io_bus_cmd_valid         (core_reader_io_bus_cmd_valid             ), //o
    .io_bus_cmd_ready         (core_arb_io_read_cmd_ready               ), //i
    .io_bus_cmd_payload_write (core_reader_io_bus_cmd_payload_write     ), //o
    .io_bus_cmd_payload_addr  (core_reader_io_bus_cmd_payload_addr[29:0]), //o
    .io_bus_cmd_payload_bl    (core_reader_io_bus_cmd_payload_bl[5:0]   ), //o
    .io_bus_wr_valid          (core_reader_io_bus_wr_valid              ), //o
    .io_bus_wr_ready          (core_arb_io_read_wr_ready                ), //i
    .io_bus_wr_payload        (core_reader_io_bus_wr_payload[127:0]     ), //o
    .io_bus_rd_valid          (core_arb_io_read_rd_valid                ), //i
    .io_bus_rd_ready          (core_reader_io_bus_rd_ready              ), //o
    .io_bus_rd_payload        (core_arb_io_read_rd_payload[127:0]       ), //i
    .io_base                  (core_reader_io_base[29:0]                ), //i
    .io_frameStart            (core_vga_io_frameStart                   ), //i
    .io_enable                (core_calib                               ), //i
    .io_pixels_valid          (core_reader_io_pixels_valid              ), //o
    .io_pixels_ready          (core_vga_io_pixels_ready                 ), //i
    .io_pixels_payload_r      (core_reader_io_pixels_payload_r[2:0]     ), //o
    .io_pixels_payload_g      (core_reader_io_pixels_payload_g[2:0]     ), //o
    .io_pixels_payload_b      (core_reader_io_pixels_payload_b[1:0]     ), //o
    .io_underflow             (core_reader_io_underflow                 ), //o
    .c3_clk0                  (mcb_mcb_c3_clk0                          ), //i
    .c3_rst0                  (mcb_mcb_c3_rst0                          )  //i
  );
  FbPainter core_painter (
    .io_bus_cmd_valid         (core_painter_io_bus_cmd_valid             ), //o
    .io_bus_cmd_ready         (core_arb_io_paint_cmd_ready               ), //i
    .io_bus_cmd_payload_write (core_painter_io_bus_cmd_payload_write     ), //o
    .io_bus_cmd_payload_addr  (core_painter_io_bus_cmd_payload_addr[29:0]), //o
    .io_bus_cmd_payload_bl    (core_painter_io_bus_cmd_payload_bl[5:0]   ), //o
    .io_bus_wr_valid          (core_painter_io_bus_wr_valid              ), //o
    .io_bus_wr_ready          (core_arb_io_paint_wr_ready                ), //i
    .io_bus_wr_payload        (core_painter_io_bus_wr_payload[127:0]     ), //o
    .io_bus_rd_valid          (core_arb_io_paint_rd_valid                ), //i
    .io_bus_rd_ready          (core_painter_io_bus_rd_ready              ), //o
    .io_bus_rd_payload        (core_arb_io_paint_rd_payload[127:0]       ), //i
    .io_base                  (30'h0                                     ), //i
    .io_start                 (core_calibRise                            ), //i
    .io_busy                  (core_painter_io_busy                      ), //o
    .c3_clk0                  (mcb_mcb_c3_clk0                           ), //i
    .c3_rst0                  (mcb_mcb_c3_rst0                           )  //i
  );
  UartFbLoader core_loader (
    .io_uart_txd              (core_loader_io_uart_txd                  ), //o
    .io_uart_rxd              (UART_RX                                  ), //i
    .io_bus_cmd_valid         (core_loader_io_bus_cmd_valid             ), //o
    .io_bus_cmd_ready         (core_arb_io_host_cmd_ready               ), //i
    .io_bus_cmd_payload_write (core_loader_io_bus_cmd_payload_write     ), //o
    .io_bus_cmd_payload_addr  (core_loader_io_bus_cmd_payload_addr[29:0]), //o
    .io_bus_cmd_payload_bl    (core_loader_io_bus_cmd_payload_bl[5:0]   ), //o
    .io_bus_wr_valid          (core_loader_io_bus_wr_valid              ), //o
    .io_bus_wr_ready          (core_arb_io_host_wr_ready                ), //i
    .io_bus_wr_payload        (core_loader_io_bus_wr_payload[127:0]     ), //o
    .io_bus_rd_valid          (core_arb_io_host_rd_valid                ), //i
    .io_bus_rd_ready          (core_loader_io_bus_rd_ready              ), //o
    .io_bus_rd_payload        (core_arb_io_host_rd_payload[127:0]       ), //i
    .io_enable                (core_loader_io_enable                    ), //i
    .io_show                  (core_loader_io_show                      ), //o
    .io_busy                  (core_loader_io_busy                      ), //o
    .io_bytePulse             (core_loader_io_bytePulse                 ), //o
    .io_ok                    (core_loader_io_ok                        ), //o
    .io_error                 (core_loader_io_error                     ), //o
    .c3_clk0                  (mcb_mcb_c3_clk0                          ), //i
    .c3_rst0                  (mcb_mcb_c3_rst0                          )  //i
  );
  FbArbiter core_arb (
    .io_read_cmd_valid          (core_reader_io_bus_cmd_valid              ), //i
    .io_read_cmd_ready          (core_arb_io_read_cmd_ready                ), //o
    .io_read_cmd_payload_write  (core_reader_io_bus_cmd_payload_write      ), //i
    .io_read_cmd_payload_addr   (core_reader_io_bus_cmd_payload_addr[29:0] ), //i
    .io_read_cmd_payload_bl     (core_reader_io_bus_cmd_payload_bl[5:0]    ), //i
    .io_read_wr_valid           (core_reader_io_bus_wr_valid               ), //i
    .io_read_wr_ready           (core_arb_io_read_wr_ready                 ), //o
    .io_read_wr_payload         (core_reader_io_bus_wr_payload[127:0]      ), //i
    .io_read_rd_valid           (core_arb_io_read_rd_valid                 ), //o
    .io_read_rd_ready           (core_reader_io_bus_rd_ready               ), //i
    .io_read_rd_payload         (core_arb_io_read_rd_payload[127:0]        ), //o
    .io_paint_cmd_valid         (core_painter_io_bus_cmd_valid             ), //i
    .io_paint_cmd_ready         (core_arb_io_paint_cmd_ready               ), //o
    .io_paint_cmd_payload_write (core_painter_io_bus_cmd_payload_write     ), //i
    .io_paint_cmd_payload_addr  (core_painter_io_bus_cmd_payload_addr[29:0]), //i
    .io_paint_cmd_payload_bl    (core_painter_io_bus_cmd_payload_bl[5:0]   ), //i
    .io_paint_wr_valid          (core_painter_io_bus_wr_valid              ), //i
    .io_paint_wr_ready          (core_arb_io_paint_wr_ready                ), //o
    .io_paint_wr_payload        (core_painter_io_bus_wr_payload[127:0]     ), //i
    .io_paint_rd_valid          (core_arb_io_paint_rd_valid                ), //o
    .io_paint_rd_ready          (core_painter_io_bus_rd_ready              ), //i
    .io_paint_rd_payload        (core_arb_io_paint_rd_payload[127:0]       ), //o
    .io_host_cmd_valid          (core_loader_io_bus_cmd_valid              ), //i
    .io_host_cmd_ready          (core_arb_io_host_cmd_ready                ), //o
    .io_host_cmd_payload_write  (core_loader_io_bus_cmd_payload_write      ), //i
    .io_host_cmd_payload_addr   (core_loader_io_bus_cmd_payload_addr[29:0] ), //i
    .io_host_cmd_payload_bl     (core_loader_io_bus_cmd_payload_bl[5:0]    ), //i
    .io_host_wr_valid           (core_loader_io_bus_wr_valid               ), //i
    .io_host_wr_ready           (core_arb_io_host_wr_ready                 ), //o
    .io_host_wr_payload         (core_loader_io_bus_wr_payload[127:0]      ), //i
    .io_host_rd_valid           (core_arb_io_host_rd_valid                 ), //o
    .io_host_rd_ready           (core_loader_io_bus_rd_ready               ), //i
    .io_host_rd_payload         (core_arb_io_host_rd_payload[127:0]        ), //o
    .io_mem_cmd_valid           (core_arb_io_mem_cmd_valid                 ), //o
    .io_mem_cmd_ready           (mcb_port_cmd_ready                        ), //i
    .io_mem_cmd_payload_write   (core_arb_io_mem_cmd_payload_write         ), //o
    .io_mem_cmd_payload_addr    (core_arb_io_mem_cmd_payload_addr[29:0]    ), //o
    .io_mem_cmd_payload_bl      (core_arb_io_mem_cmd_payload_bl[5:0]       ), //o
    .io_mem_wr_valid            (core_arb_io_mem_wr_valid                  ), //o
    .io_mem_wr_ready            (mcb_port_wr_ready                         ), //i
    .io_mem_wr_payload          (core_arb_io_mem_wr_payload[127:0]         ), //o
    .io_mem_rd_valid            (mcb_port_rd_valid                         ), //i
    .io_mem_rd_ready            (core_arb_io_mem_rd_ready                  ), //o
    .io_mem_rd_payload          (mcb_port_rd_payload[127:0]                )  //i
  );
  FbStatus core_status (
    .io_calib         (core_calib                       ), //i
    .io_faults        (core_status_io_faults[3:0]       ), //i
    .io_frameStart    (core_vga_io_frameStart           ), //i
    .io_bytePulse     (core_loader_io_bytePulse         ), //i
    .io_digits_0_code (core_status_io_digits_0_code[3:0]), //o
    .io_digits_0_dot  (core_status_io_digits_0_dot      ), //o
    .io_digits_1_code (core_status_io_digits_1_code[3:0]), //o
    .io_digits_1_dot  (core_status_io_digits_1_dot      ), //o
    .io_digits_2_code (core_status_io_digits_2_code[3:0]), //o
    .io_digits_2_dot  (core_status_io_digits_2_dot      ), //o
    .c3_clk0          (mcb_mcb_c3_clk0                  ), //i
    .c3_rst0          (mcb_mcb_c3_rst0                  )  //i
  );
  SevenSegMux core_ss (
    .io_digits_0_code (core_status_io_digits_0_code[3:0]), //i
    .io_digits_0_dot  (core_status_io_digits_0_dot      ), //i
    .io_digits_1_code (core_status_io_digits_1_code[3:0]), //i
    .io_digits_1_dot  (core_status_io_digits_1_dot      ), //i
    .io_digits_2_code (core_status_io_digits_2_code[3:0]), //i
    .io_digits_2_dot  (core_status_io_digits_2_dot      ), //i
    .io_seg           (core_ss_io_seg[7:0]              ), //o
    .io_en            (core_ss_io_en[2:0]               ), //o
    .c3_clk0          (mcb_mcb_c3_clk0                  ), //i
    .c3_rst0          (mcb_mcb_c3_rst0                  )  //i
  );
  assign mcb_port_cmd_fire = (mcb_port_cmd_valid && mcb_port_cmd_ready);
  assign mcb_mcb_c3_p0_cmd_bl = mcb_port_cmd_payload_bl;
  assign mcb_mcb_c3_p0_cmd_byte_addr = mcb_port_cmd_payload_addr;
  assign mcb_port_cmd_ready = (! mcb_mcb_c3_p0_cmd_full);
  assign mcb_port_wr_fire = (mcb_port_wr_valid && mcb_port_wr_ready);
  assign mcb_port_wr_ready = (! mcb_mcb_c3_p0_wr_full);
  assign mcb_port_rd_valid = (! mcb_mcb_c3_p0_rd_empty);
  assign mcb_port_rd_payload = mcb_mcb_c3_p0_rd_data;
  assign mcb_port_rd_fire = (mcb_port_rd_valid && mcb_port_rd_ready);
  assign mcb3_dram_a = mcb_mcb_mcb3_dram_a;
  assign mcb3_dram_ba = mcb_mcb_mcb3_dram_ba;
  assign mcb3_dram_ras_n = mcb_mcb_mcb3_dram_ras_n;
  assign mcb3_dram_cas_n = mcb_mcb_mcb3_dram_cas_n;
  assign mcb3_dram_we_n = mcb_mcb_mcb3_dram_we_n;
  assign mcb3_dram_cke = mcb_mcb_mcb3_dram_cke;
  assign mcb3_dram_ck = mcb_mcb_mcb3_dram_ck;
  assign mcb3_dram_ck_n = mcb_mcb_mcb3_dram_ck_n;
  assign mcb3_dram_dm = mcb_mcb_mcb3_dram_dm;
  assign mcb3_dram_udm = mcb_mcb_mcb3_dram_udm;
  assign core_calib = c3_calib_done_buffercc_io_dataOut;
  assign core_calibRise = (core_calib && (! core_calib_regNext));
  assign core_vga_io_softReset = (! core_calib);
  assign mcb_port_cmd_valid = core_arb_io_mem_cmd_valid;
  assign mcb_port_cmd_payload_instr = (core_arb_io_mem_cmd_payload_write ? 3'b000 : 3'b001);
  assign mcb_port_cmd_payload_bl = core_arb_io_mem_cmd_payload_bl;
  assign mcb_port_cmd_payload_addr = core_arb_io_mem_cmd_payload_addr;
  assign mcb_port_wr_valid = core_arb_io_mem_wr_valid;
  assign mcb_port_wr_payload_data = core_arb_io_mem_wr_payload;
  assign mcb_port_wr_payload_mask = 16'h0;
  assign mcb_port_rd_ready = core_arb_io_mem_rd_ready;
  assign core_reader_io_base = (core_showB ? 30'h00100000 : 30'h0);
  assign core_loader_io_enable = (core_calib && (! core_painter_io_busy));
  assign HSync = core_vga_io_vga_hSync;
  assign VSync = core_vga_io_vga_vSync;
  assign Red = (core_vga_io_vga_colorEn ? core_vga_io_vga_color_r : 3'b000);
  assign Green = (core_vga_io_vga_colorEn ? core_vga_io_vga_color_g : 3'b000);
  assign Blue = (core_vga_io_vga_colorEn ? core_vga_io_vga_color_b : 2'b00);
  assign UART_TX = core_loader_io_uart_txd;
  assign core_status_io_faults = {{{mcb_mcb_c3_p0_wr_underrun,mcb_mcb_c3_p0_wr_error},mcb_mcb_c3_p0_rd_overflow},mcb_mcb_c3_p0_rd_error};
  assign seg = core_ss_io_seg;
  assign en = core_ss_io_en;
  assign when_VgaFbDemoTop_l143 = (core_calib && (core_reader_io_underflow || core_vga_io_error));
  assign when_VgaFbDemoTop_l144 = (|{{{mcb_mcb_c3_p0_wr_underrun,mcb_mcb_c3_p0_wr_error},mcb_mcb_c3_p0_rd_overflow},mcb_mcb_c3_p0_rd_error});
  assign calib_done = core_calib;
  assign test_done = core_okLatch;
  assign test_error = core_errLatch;
  assign data_error = core_underLatch;
  assign mcb_fault = core_faultLatch;
  always @(posedge mcb_mcb_c3_clk0) begin
    if(mcb_mcb_c3_rst0) begin
      core_calib_regNext <= 1'b0;
      core_showB <= 1'b0;
      core_okLatch <= 1'b0;
      core_errLatch <= 1'b0;
      core_underLatch <= 1'b0;
      core_faultLatch <= 1'b0;
    end else begin
      core_calib_regNext <= core_calib;
      if(core_vga_io_frameStart) begin
        core_showB <= core_loader_io_show;
      end
      if(core_loader_io_ok) begin
        core_okLatch <= 1'b1;
      end
      if(core_loader_io_error) begin
        core_errLatch <= 1'b1;
      end
      if(when_VgaFbDemoTop_l143) begin
        core_underLatch <= 1'b1;
      end
      if(when_VgaFbDemoTop_l144) begin
        core_faultLatch <= 1'b1;
      end
    end
  end


endmodule

module SevenSegMux (
  input  wire [3:0]    io_digits_0_code,
  input  wire          io_digits_0_dot,
  input  wire [3:0]    io_digits_1_code,
  input  wire          io_digits_1_dot,
  input  wire [3:0]    io_digits_2_code,
  input  wire          io_digits_2_dot,
  output wire [7:0]    io_seg,
  output wire [2:0]    io_en,
  input  wire          c3_clk0,
  input  wire          c3_rst0
);

  wire       [13:0]   _zz_slot;
  wire       [1:0]    _zz_index;
  reg        [3:0]    _zz_current_code;
  reg                 _zz_current_dot;
  reg        [6:0]    _zz_shown;
  wire       [2:0]    _zz_io_en;
  reg        [13:0]   slot;
  wire                slotLast;
  reg        [1:0]    index;
  wire                blanking;
  wire                lit;
  wire       [6:0]    lut_0;
  wire       [6:0]    lut_1;
  wire       [6:0]    lut_2;
  wire       [6:0]    lut_3;
  wire       [6:0]    lut_4;
  wire       [6:0]    lut_5;
  wire       [6:0]    lut_6;
  wire       [6:0]    lut_7;
  wire       [6:0]    lut_8;
  wire       [6:0]    lut_9;
  wire       [6:0]    lut_10;
  wire       [6:0]    lut_11;
  wire       [6:0]    lut_12;
  wire       [6:0]    lut_13;
  wire       [6:0]    lut_14;
  wire       [6:0]    lut_15;
  wire       [3:0]    current_code;
  wire                current_dot;
  wire       [7:0]    shown;

  assign _zz_slot = (slot + 14'h0001);
  assign _zz_index = (index + 2'b01);
  assign _zz_io_en = (3'b001 <<< index);
  always @(*) begin
    case(index)
      2'b00 : begin
        _zz_current_code = io_digits_0_code;
        _zz_current_dot = io_digits_0_dot;
      end
      2'b01 : begin
        _zz_current_code = io_digits_1_code;
        _zz_current_dot = io_digits_1_dot;
      end
      default : begin
        _zz_current_code = io_digits_2_code;
        _zz_current_dot = io_digits_2_dot;
      end
    endcase
  end

  always @(*) begin
    case(current_code)
      4'b0000 : _zz_shown = lut_0;
      4'b0001 : _zz_shown = lut_1;
      4'b0010 : _zz_shown = lut_2;
      4'b0011 : _zz_shown = lut_3;
      4'b0100 : _zz_shown = lut_4;
      4'b0101 : _zz_shown = lut_5;
      4'b0110 : _zz_shown = lut_6;
      4'b0111 : _zz_shown = lut_7;
      4'b1000 : _zz_shown = lut_8;
      4'b1001 : _zz_shown = lut_9;
      4'b1010 : _zz_shown = lut_10;
      4'b1011 : _zz_shown = lut_11;
      4'b1100 : _zz_shown = lut_12;
      4'b1101 : _zz_shown = lut_13;
      4'b1110 : _zz_shown = lut_14;
      default : _zz_shown = lut_15;
    endcase
  end

  assign slotLast = (slot == 14'h208c);
  assign blanking = (14'h204d <= slot);
  assign lit = (! blanking);
  assign lut_0 = 7'h3f;
  assign lut_1 = 7'h06;
  assign lut_2 = 7'h5b;
  assign lut_3 = 7'h4f;
  assign lut_4 = 7'h66;
  assign lut_5 = 7'h6d;
  assign lut_6 = 7'h7d;
  assign lut_7 = 7'h07;
  assign lut_8 = 7'h7f;
  assign lut_9 = 7'h6f;
  assign lut_10 = 7'h0;
  assign lut_11 = 7'h40;
  assign lut_12 = 7'h79;
  assign lut_13 = 7'h0;
  assign lut_14 = 7'h0;
  assign lut_15 = 7'h0;
  assign current_code = _zz_current_code;
  assign current_dot = _zz_current_dot;
  assign shown = {current_dot,_zz_shown};
  assign io_seg = (~ (lit ? shown : 8'h0));
  assign io_en = (~ (lit ? _zz_io_en : 3'b000));
  always @(posedge c3_clk0) begin
    if(c3_rst0) begin
      slot <= 14'h0;
      index <= 2'b00;
    end else begin
      slot <= (slotLast ? 14'h0 : _zz_slot);
      if(slotLast) begin
        index <= ((index == 2'b10) ? 2'b00 : _zz_index);
      end
    end
  end


endmodule

module FbStatus (
  input  wire          io_calib,
  input  wire [3:0]    io_faults,
  input  wire          io_frameStart,
  input  wire          io_bytePulse,
  output reg  [3:0]    io_digits_0_code,
  output reg           io_digits_0_dot,
  output reg  [3:0]    io_digits_1_code,
  output reg           io_digits_1_dot,
  output reg  [3:0]    io_digits_2_code,
  output reg           io_digits_2_dot,
  input  wire          c3_clk0,
  input  wire          c3_rst0
);

  wire                kbCnt_io_inc;
  wire       [3:0]    fpsCnt_io_digits_0;
  wire       [3:0]    fpsCnt_io_digits_1;
  wire       [3:0]    fpsCnt_io_digits_2;
  wire       [3:0]    kbCnt_io_digits_0;
  wire       [3:0]    kbCnt_io_digits_1;
  wire       [3:0]    kbCnt_io_digits_2;
  wire       [24:0]   _zz_secondCnt;
  reg        [24:0]   secondCnt;
  wire                secondTick;
  reg        [3:0]    fps_0;
  reg        [3:0]    fps_1;
  reg        [3:0]    fps_2;
  reg        [9:0]    byteCnt;
  reg        [24:0]   hold;
  wire                when_FbStatus_l78;
  wire                uploading;
  reg        [3:0]    faultLatch;
  wire                fault;
  reg        [3:0]    faultCode;
  wire                when_FbStatus_l88;
  wire                when_FbStatus_l89;
  wire                when_FbStatus_l90;
  wire                when_FbStatus_l91;
  wire       [3:0]    value_0;
  wire       [3:0]    value_1;
  wire       [3:0]    value_2;
  wire                blankH;
  wire                blankT;
  wire       [3:0]    blank;
  wire                when_FbStatus_l106;

  assign _zz_secondCnt = (secondCnt + 25'h0000001);
  Bcd3 fpsCnt (
    .io_inc      (io_frameStart          ), //i
    .io_clear    (secondTick             ), //i
    .io_digits_0 (fpsCnt_io_digits_0[3:0]), //o
    .io_digits_1 (fpsCnt_io_digits_1[3:0]), //o
    .io_digits_2 (fpsCnt_io_digits_2[3:0]), //o
    .c3_clk0     (c3_clk0                ), //i
    .c3_rst0     (c3_rst0                )  //i
  );
  Bcd3 kbCnt (
    .io_inc      (kbCnt_io_inc          ), //i
    .io_clear    (1'b0                  ), //i
    .io_digits_0 (kbCnt_io_digits_0[3:0]), //o
    .io_digits_1 (kbCnt_io_digits_1[3:0]), //o
    .io_digits_2 (kbCnt_io_digits_2[3:0]), //o
    .c3_clk0     (c3_clk0               ), //i
    .c3_rst0     (c3_rst0               )  //i
  );
  assign secondTick = (secondCnt == 25'h17d783f);
  assign kbCnt_io_inc = (io_bytePulse && (byteCnt == 10'h3ff));
  assign when_FbStatus_l78 = (hold != 25'h0);
  assign uploading = (hold != 25'h0);
  assign fault = (|faultLatch);
  always @(*) begin
    faultCode = 4'b0000;
    if(when_FbStatus_l88) begin
      faultCode = 4'b0100;
    end
    if(when_FbStatus_l89) begin
      faultCode = 4'b0011;
    end
    if(when_FbStatus_l90) begin
      faultCode = 4'b0010;
    end
    if(when_FbStatus_l91) begin
      faultCode = 4'b0001;
    end
  end

  assign when_FbStatus_l88 = faultLatch[0];
  assign when_FbStatus_l89 = faultLatch[1];
  assign when_FbStatus_l90 = faultLatch[2];
  assign when_FbStatus_l91 = faultLatch[3];
  assign value_0 = (uploading ? kbCnt_io_digits_0 : fps_0);
  assign value_1 = (uploading ? kbCnt_io_digits_1 : fps_1);
  assign value_2 = (uploading ? kbCnt_io_digits_2 : fps_2);
  assign blankH = (value_2 == 4'b0000);
  assign blankT = (blankH && (value_1 == 4'b0000));
  assign blank = 4'b1010;
  always @(*) begin
    io_digits_0_code = (blankH ? blank : value_2);
    if(when_FbStatus_l106) begin
      io_digits_0_code = 4'b1011;
    end
    if(fault) begin
      io_digits_0_code = 4'b1100;
    end
  end

  always @(*) begin
    io_digits_1_code = (blankT ? blank : value_1);
    if(when_FbStatus_l106) begin
      io_digits_1_code = 4'b1011;
    end
    if(fault) begin
      io_digits_1_code = 4'b1100;
    end
  end

  always @(*) begin
    io_digits_2_code = value_0;
    if(when_FbStatus_l106) begin
      io_digits_2_code = 4'b1011;
    end
    if(fault) begin
      io_digits_2_code = faultCode;
    end
  end

  always @(*) begin
    io_digits_0_dot = uploading;
    if(when_FbStatus_l106) begin
      io_digits_0_dot = 1'b0;
    end
    if(fault) begin
      io_digits_0_dot = 1'b0;
    end
  end

  always @(*) begin
    io_digits_1_dot = uploading;
    if(when_FbStatus_l106) begin
      io_digits_1_dot = 1'b0;
    end
    if(fault) begin
      io_digits_1_dot = 1'b0;
    end
  end

  always @(*) begin
    io_digits_2_dot = uploading;
    if(when_FbStatus_l106) begin
      io_digits_2_dot = 1'b0;
    end
    if(fault) begin
      io_digits_2_dot = 1'b0;
    end
  end

  assign when_FbStatus_l106 = (! io_calib);
  always @(posedge c3_clk0) begin
    if(c3_rst0) begin
      secondCnt <= 25'h0;
      fps_0 <= 4'b0000;
      fps_1 <= 4'b0000;
      fps_2 <= 4'b0000;
      byteCnt <= 10'h0;
      hold <= 25'h0;
      faultLatch <= 4'b0000;
    end else begin
      secondCnt <= (secondTick ? 25'h0 : _zz_secondCnt);
      if(secondTick) begin
        fps_0 <= fpsCnt_io_digits_0;
        fps_1 <= fpsCnt_io_digits_1;
        fps_2 <= fpsCnt_io_digits_2;
      end
      if(io_bytePulse) begin
        byteCnt <= (byteCnt + 10'h001);
      end
      if(when_FbStatus_l78) begin
        hold <= (hold - 25'h0000001);
      end
      if(io_bytePulse) begin
        hold <= 25'h17d783f;
      end
      faultLatch <= (faultLatch | io_faults);
    end
  end


endmodule

module FbArbiter (
  input  wire          io_read_cmd_valid,
  output wire          io_read_cmd_ready,
  input  wire          io_read_cmd_payload_write,
  input  wire [29:0]   io_read_cmd_payload_addr,
  input  wire [5:0]    io_read_cmd_payload_bl,
  input  wire          io_read_wr_valid,
  output wire          io_read_wr_ready,
  input  wire [127:0]  io_read_wr_payload,
  output wire          io_read_rd_valid,
  input  wire          io_read_rd_ready,
  output wire [127:0]  io_read_rd_payload,
  input  wire          io_paint_cmd_valid,
  output wire          io_paint_cmd_ready,
  input  wire          io_paint_cmd_payload_write,
  input  wire [29:0]   io_paint_cmd_payload_addr,
  input  wire [5:0]    io_paint_cmd_payload_bl,
  input  wire          io_paint_wr_valid,
  output wire          io_paint_wr_ready,
  input  wire [127:0]  io_paint_wr_payload,
  output wire          io_paint_rd_valid,
  input  wire          io_paint_rd_ready,
  output wire [127:0]  io_paint_rd_payload,
  input  wire          io_host_cmd_valid,
  output wire          io_host_cmd_ready,
  input  wire          io_host_cmd_payload_write,
  input  wire [29:0]   io_host_cmd_payload_addr,
  input  wire [5:0]    io_host_cmd_payload_bl,
  input  wire          io_host_wr_valid,
  output wire          io_host_wr_ready,
  input  wire [127:0]  io_host_wr_payload,
  output wire          io_host_rd_valid,
  input  wire          io_host_rd_ready,
  output wire [127:0]  io_host_rd_payload,
  output wire          io_mem_cmd_valid,
  input  wire          io_mem_cmd_ready,
  output wire          io_mem_cmd_payload_write,
  output wire [29:0]   io_mem_cmd_payload_addr,
  output wire [5:0]    io_mem_cmd_payload_bl,
  output wire          io_mem_wr_valid,
  input  wire          io_mem_wr_ready,
  output wire [127:0]  io_mem_wr_payload,
  input  wire          io_mem_rd_valid,
  output wire          io_mem_rd_ready,
  input  wire [127:0]  io_mem_rd_payload
);

  wire                grantPaint;
  wire                grantHost;

  assign grantPaint = ((! io_read_cmd_valid) && io_paint_cmd_valid);
  assign grantHost = (((! io_read_cmd_valid) && (! grantPaint)) && io_host_cmd_valid);
  assign io_mem_cmd_valid = ((io_read_cmd_valid || io_paint_cmd_valid) || io_host_cmd_valid);
  assign io_mem_cmd_payload_write = (io_read_cmd_valid ? io_read_cmd_payload_write : (grantPaint ? io_paint_cmd_payload_write : io_host_cmd_payload_write));
  assign io_mem_cmd_payload_addr = (io_read_cmd_valid ? io_read_cmd_payload_addr : (grantPaint ? io_paint_cmd_payload_addr : io_host_cmd_payload_addr));
  assign io_mem_cmd_payload_bl = (io_read_cmd_valid ? io_read_cmd_payload_bl : (grantPaint ? io_paint_cmd_payload_bl : io_host_cmd_payload_bl));
  assign io_read_cmd_ready = (io_read_cmd_valid && io_mem_cmd_ready);
  assign io_paint_cmd_ready = (grantPaint && io_mem_cmd_ready);
  assign io_host_cmd_ready = (grantHost && io_mem_cmd_ready);
  assign io_mem_wr_valid = (io_paint_wr_valid || io_host_wr_valid);
  assign io_mem_wr_payload = (io_paint_wr_valid ? io_paint_wr_payload : io_host_wr_payload);
  assign io_paint_wr_ready = (io_paint_wr_valid && io_mem_wr_ready);
  assign io_host_wr_ready = ((! io_paint_wr_valid) && io_mem_wr_ready);
  assign io_read_wr_ready = 1'b0;
  assign io_read_rd_valid = io_mem_rd_valid;
  assign io_read_rd_payload = io_mem_rd_payload;
  assign io_mem_rd_ready = io_read_rd_ready;
  assign io_paint_rd_valid = 1'b0;
  assign io_paint_rd_payload = 128'h0;
  assign io_host_rd_valid = 1'b0;
  assign io_host_rd_payload = 128'h0;

endmodule

module UartFbLoader (
  output wire          io_uart_txd,
  input  wire          io_uart_rxd,
  output wire          io_bus_cmd_valid,
  input  wire          io_bus_cmd_ready,
  output wire          io_bus_cmd_payload_write,
  output wire [29:0]   io_bus_cmd_payload_addr,
  output wire [5:0]    io_bus_cmd_payload_bl,
  output wire          io_bus_wr_valid,
  input  wire          io_bus_wr_ready,
  output wire [127:0]  io_bus_wr_payload,
  input  wire          io_bus_rd_valid,
  output wire          io_bus_rd_ready,
  input  wire [127:0]  io_bus_rd_payload,
  input  wire          io_enable,
  output wire          io_show,
  output wire          io_busy,
  output wire          io_bytePulse,
  output wire          io_ok,
  output wire          io_error,
  input  wire          c3_clk0,
  input  wire          c3_rst0
);
  localparam UartParityType_NONE = 2'd0;
  localparam UartParityType_EVEN = 2'd1;
  localparam UartParityType_ODD = 2'd2;
  localparam UartStopType_ONE = 1'd0;
  localparam UartStopType_TWO = 1'd1;
  localparam fsm_1_BOOT = 3'd0;
  localparam fsm_1_sSync0 = 3'd1;
  localparam fsm_1_sSync1 = 3'd2;
  localparam fsm_1_sCmd = 3'd3;
  localparam fsm_1_sArgs = 3'd4;
  localparam fsm_1_sData = 3'd5;
  localparam fsm_1_sCrc = 3'd6;
  localparam fsm_1_sAck = 3'd7;

  wire                ctrl_io_write_ready;
  wire                ctrl_io_read_valid;
  wire       [7:0]    ctrl_io_read_payload;
  wire                ctrl_io_uart_txd;
  wire                ctrl_io_readError;
  wire                ctrl_io_readBreak;
  wire       [3:0]    _zz_when_UartFbLoader_l163;
  wire       [32:0]   _zz_when_UartFbLoader_l173;
  wire                rxValid;
  wire       [7:0]    rxU;
  reg                 tx_valid;
  wire                tx_ready;
  reg        [7:0]    tx_payload;
  reg                 push_pending;
  reg                 push_dataDone;
  reg        [127:0]  push_word;
  reg        [29:0]   push_addr;
  wire                io_bus_wr_fire;
  wire                io_bus_cmd_fire;
  reg        [7:0]    cmdReg;
  reg        [3:0]    argIdx;
  reg        [3:0]    argCnt;
  reg        [63:0]   acc;
  reg        [127:0]  wacc;
  reg        [3:0]    byteIdx;
  reg        [31:0]   remaining;
  reg        [7:0]    crc;
  reg                 ackOk;
  reg                 flowErr;
  reg                 showReg;
  wire       [63:0]   accNext;
  wire       [127:0]  waccNext;
  wire                fsm_wantExit;
  reg                 fsm_wantStart;
  wire                fsm_wantKill;
  wire                tx_fire;
  reg        [2:0]    fsm_stateReg;
  reg        [2:0]    fsm_stateNext;
  wire                when_UartFbLoader_l135;
  wire                when_UartFbLoader_l140;
  wire                when_UartFbLoader_l141;
  wire                when_UartFbLoader_l163;
  wire                when_UartFbLoader_l164;
  wire       [31:0]   _zz_push_addr;
  wire       [31:0]   _zz_remaining;
  wire                when_UartFbLoader_l173;
  wire                when_UartFbLoader_l198;
  wire                when_UartFbLoader_l203;
  wire                fsm_onExit_BOOT;
  wire                fsm_onExit_sSync0;
  wire                fsm_onExit_sSync1;
  wire                fsm_onExit_sCmd;
  wire                fsm_onExit_sArgs;
  wire                fsm_onExit_sData;
  wire                fsm_onExit_sCrc;
  wire                fsm_onExit_sAck;
  wire                fsm_onEntry_BOOT;
  wire                fsm_onEntry_sSync0;
  wire                fsm_onEntry_sSync1;
  wire                fsm_onEntry_sCmd;
  wire                fsm_onEntry_sArgs;
  wire                fsm_onEntry_sData;
  wire                fsm_onEntry_sCrc;
  wire                fsm_onEntry_sAck;
  `ifndef SYNTHESIS
  reg [47:0] fsm_stateReg_string;
  reg [47:0] fsm_stateNext_string;
  `endif


  assign _zz_when_UartFbLoader_l163 = (argCnt - 4'b0001);
  assign _zz_when_UartFbLoader_l173 = ({1'b0,_zz_push_addr} + {1'b0,_zz_remaining});
  UartCtrl ctrl (
    .io_config_frame_dataLength (3'b111                   ), //i
    .io_config_frame_stop       (UartStopType_ONE         ), //i
    .io_config_frame_parity     (UartParityType_NONE      ), //i
    .io_config_clockDivider     (20'h000a2                ), //i
    .io_write_valid             (tx_valid                 ), //i
    .io_write_ready             (ctrl_io_write_ready      ), //o
    .io_write_payload           (tx_payload[7:0]          ), //i
    .io_read_valid              (ctrl_io_read_valid       ), //o
    .io_read_ready              (1'b1                     ), //i
    .io_read_payload            (ctrl_io_read_payload[7:0]), //o
    .io_uart_txd                (ctrl_io_uart_txd         ), //o
    .io_uart_rxd                (io_uart_rxd              ), //i
    .io_readError               (ctrl_io_readError        ), //o
    .io_writeBreak              (1'b0                     ), //i
    .io_readBreak               (ctrl_io_readBreak        ), //o
    .c3_clk0                    (c3_clk0                  ), //i
    .c3_rst0                    (c3_rst0                  )  //i
  );
  `ifndef SYNTHESIS
  always @(*) begin
    case(fsm_stateReg)
      fsm_1_BOOT : fsm_stateReg_string = "BOOT  ";
      fsm_1_sSync0 : fsm_stateReg_string = "sSync0";
      fsm_1_sSync1 : fsm_stateReg_string = "sSync1";
      fsm_1_sCmd : fsm_stateReg_string = "sCmd  ";
      fsm_1_sArgs : fsm_stateReg_string = "sArgs ";
      fsm_1_sData : fsm_stateReg_string = "sData ";
      fsm_1_sCrc : fsm_stateReg_string = "sCrc  ";
      fsm_1_sAck : fsm_stateReg_string = "sAck  ";
      default : fsm_stateReg_string = "??????";
    endcase
  end
  always @(*) begin
    case(fsm_stateNext)
      fsm_1_BOOT : fsm_stateNext_string = "BOOT  ";
      fsm_1_sSync0 : fsm_stateNext_string = "sSync0";
      fsm_1_sSync1 : fsm_stateNext_string = "sSync1";
      fsm_1_sCmd : fsm_stateNext_string = "sCmd  ";
      fsm_1_sArgs : fsm_stateNext_string = "sArgs ";
      fsm_1_sData : fsm_stateNext_string = "sData ";
      fsm_1_sCrc : fsm_stateNext_string = "sCrc  ";
      fsm_1_sAck : fsm_stateNext_string = "sAck  ";
      default : fsm_stateNext_string = "??????";
    endcase
  end
  `endif

  assign io_bus_rd_ready = 1'b0;
  assign io_uart_txd = ctrl_io_uart_txd;
  assign rxValid = (ctrl_io_read_valid && io_enable);
  assign rxU = ctrl_io_read_payload;
  always @(*) begin
    tx_valid = 1'b0;
    case(fsm_stateReg)
      fsm_1_sSync0 : begin
      end
      fsm_1_sSync1 : begin
      end
      fsm_1_sCmd : begin
      end
      fsm_1_sArgs : begin
      end
      fsm_1_sData : begin
      end
      fsm_1_sCrc : begin
      end
      fsm_1_sAck : begin
        tx_valid = (! push_pending);
      end
      default : begin
      end
    endcase
  end

  always @(*) begin
    tx_payload = 8'h0;
    case(fsm_stateReg)
      fsm_1_sSync0 : begin
      end
      fsm_1_sSync1 : begin
      end
      fsm_1_sCmd : begin
      end
      fsm_1_sArgs : begin
      end
      fsm_1_sData : begin
      end
      fsm_1_sCrc : begin
      end
      fsm_1_sAck : begin
        tx_payload = (ackOk ? 8'h4b : 8'h45);
      end
      default : begin
      end
    endcase
  end

  assign tx_ready = ctrl_io_write_ready;
  assign io_bus_wr_valid = (push_pending && (! push_dataDone));
  assign io_bus_wr_payload = push_word;
  assign io_bus_wr_fire = (io_bus_wr_valid && io_bus_wr_ready);
  assign io_bus_cmd_valid = (push_pending && push_dataDone);
  assign io_bus_cmd_payload_write = 1'b1;
  assign io_bus_cmd_payload_addr = push_addr;
  assign io_bus_cmd_payload_bl = 6'h0;
  assign io_bus_cmd_fire = (io_bus_cmd_valid && io_bus_cmd_ready);
  assign accNext = {ctrl_io_read_payload,acc[63 : 8]};
  assign waccNext = {ctrl_io_read_payload,wacc[127 : 8]};
  assign fsm_wantExit = 1'b0;
  always @(*) begin
    fsm_wantStart = 1'b0;
    case(fsm_stateReg)
      fsm_1_sSync0 : begin
      end
      fsm_1_sSync1 : begin
      end
      fsm_1_sCmd : begin
      end
      fsm_1_sArgs : begin
      end
      fsm_1_sData : begin
      end
      fsm_1_sCrc : begin
      end
      fsm_1_sAck : begin
      end
      default : begin
        fsm_wantStart = 1'b1;
      end
    endcase
  end

  assign fsm_wantKill = 1'b0;
  assign io_show = showReg;
  assign io_busy = (! (fsm_stateReg == fsm_1_sSync0));
  assign io_bytePulse = (rxValid && (fsm_stateReg == fsm_1_sData));
  assign tx_fire = (tx_valid && tx_ready);
  assign io_ok = (tx_fire && ackOk);
  assign io_error = (tx_fire && (! ackOk));
  always @(*) begin
    fsm_stateNext = fsm_stateReg;
    case(fsm_stateReg)
      fsm_1_sSync0 : begin
        if(when_UartFbLoader_l135) begin
          fsm_stateNext = fsm_1_sSync1;
        end
      end
      fsm_1_sSync1 : begin
        if(rxValid) begin
          if(when_UartFbLoader_l140) begin
            fsm_stateNext = fsm_1_sCmd;
          end else begin
            if(when_UartFbLoader_l141) begin
              fsm_stateNext = fsm_1_sSync0;
            end
          end
        end
      end
      fsm_1_sCmd : begin
        if(rxValid) begin
          case(rxU)
            8'h01 : begin
              fsm_stateNext = fsm_1_sArgs;
            end
            8'h02 : begin
              fsm_stateNext = fsm_1_sArgs;
            end
            8'h03 : begin
              fsm_stateNext = fsm_1_sAck;
            end
            default : begin
              fsm_stateNext = fsm_1_sAck;
            end
          endcase
        end
      end
      fsm_1_sArgs : begin
        if(rxValid) begin
          if(when_UartFbLoader_l163) begin
            if(when_UartFbLoader_l164) begin
              if(when_UartFbLoader_l173) begin
                fsm_stateNext = fsm_1_sAck;
              end else begin
                fsm_stateNext = fsm_1_sData;
              end
            end else begin
              fsm_stateNext = fsm_1_sAck;
            end
          end
        end
      end
      fsm_1_sData : begin
        if(rxValid) begin
          if(when_UartFbLoader_l203) begin
            fsm_stateNext = fsm_1_sCrc;
          end
        end
      end
      fsm_1_sCrc : begin
        if(rxValid) begin
          fsm_stateNext = fsm_1_sAck;
        end
      end
      fsm_1_sAck : begin
        if(tx_fire) begin
          fsm_stateNext = fsm_1_sSync0;
        end
      end
      default : begin
      end
    endcase
    if(fsm_wantStart) begin
      fsm_stateNext = fsm_1_sSync0;
    end
    if(fsm_wantKill) begin
      fsm_stateNext = fsm_1_BOOT;
    end
  end

  assign when_UartFbLoader_l135 = (rxValid && (rxU == 8'ha5));
  assign when_UartFbLoader_l140 = (rxU == 8'h5a);
  assign when_UartFbLoader_l141 = (rxU != 8'ha5);
  assign when_UartFbLoader_l163 = (argIdx == _zz_when_UartFbLoader_l163);
  assign when_UartFbLoader_l164 = (cmdReg == 8'h01);
  assign _zz_push_addr = accNext[31 : 0];
  assign _zz_remaining = accNext[63 : 32];
  assign when_UartFbLoader_l173 = ((((_zz_push_addr[3 : 0] != 4'b0000) || (_zz_remaining[3 : 0] != 4'b0000)) || (_zz_remaining == 32'h0)) || (33'h004000000 < _zz_when_UartFbLoader_l173));
  assign when_UartFbLoader_l198 = (byteIdx == 4'b1111);
  assign when_UartFbLoader_l203 = (remaining == 32'h00000001);
  assign fsm_onExit_BOOT = ((fsm_stateNext != fsm_1_BOOT) && (fsm_stateReg == fsm_1_BOOT));
  assign fsm_onExit_sSync0 = ((fsm_stateNext != fsm_1_sSync0) && (fsm_stateReg == fsm_1_sSync0));
  assign fsm_onExit_sSync1 = ((fsm_stateNext != fsm_1_sSync1) && (fsm_stateReg == fsm_1_sSync1));
  assign fsm_onExit_sCmd = ((fsm_stateNext != fsm_1_sCmd) && (fsm_stateReg == fsm_1_sCmd));
  assign fsm_onExit_sArgs = ((fsm_stateNext != fsm_1_sArgs) && (fsm_stateReg == fsm_1_sArgs));
  assign fsm_onExit_sData = ((fsm_stateNext != fsm_1_sData) && (fsm_stateReg == fsm_1_sData));
  assign fsm_onExit_sCrc = ((fsm_stateNext != fsm_1_sCrc) && (fsm_stateReg == fsm_1_sCrc));
  assign fsm_onExit_sAck = ((fsm_stateNext != fsm_1_sAck) && (fsm_stateReg == fsm_1_sAck));
  assign fsm_onEntry_BOOT = ((fsm_stateNext == fsm_1_BOOT) && (fsm_stateReg != fsm_1_BOOT));
  assign fsm_onEntry_sSync0 = ((fsm_stateNext == fsm_1_sSync0) && (fsm_stateReg != fsm_1_sSync0));
  assign fsm_onEntry_sSync1 = ((fsm_stateNext == fsm_1_sSync1) && (fsm_stateReg != fsm_1_sSync1));
  assign fsm_onEntry_sCmd = ((fsm_stateNext == fsm_1_sCmd) && (fsm_stateReg != fsm_1_sCmd));
  assign fsm_onEntry_sArgs = ((fsm_stateNext == fsm_1_sArgs) && (fsm_stateReg != fsm_1_sArgs));
  assign fsm_onEntry_sData = ((fsm_stateNext == fsm_1_sData) && (fsm_stateReg != fsm_1_sData));
  assign fsm_onEntry_sCrc = ((fsm_stateNext == fsm_1_sCrc) && (fsm_stateReg != fsm_1_sCrc));
  assign fsm_onEntry_sAck = ((fsm_stateNext == fsm_1_sAck) && (fsm_stateReg != fsm_1_sAck));
  always @(posedge c3_clk0) begin
    if(c3_rst0) begin
      push_pending <= 1'b0;
      push_dataDone <= 1'b0;
      push_word <= 128'h0;
      push_addr <= 30'h0;
      cmdReg <= 8'h0;
      argIdx <= 4'b0000;
      argCnt <= 4'b0000;
      acc <= 64'h0;
      wacc <= 128'h0;
      byteIdx <= 4'b0000;
      remaining <= 32'h0;
      crc <= 8'h0;
      ackOk <= 1'b0;
      flowErr <= 1'b0;
      showReg <= 1'b0;
      fsm_stateReg <= fsm_1_BOOT;
    end else begin
      if(io_bus_wr_fire) begin
        push_dataDone <= 1'b1;
      end
      if(io_bus_cmd_fire) begin
        push_pending <= 1'b0;
        push_dataDone <= 1'b0;
        push_addr <= (push_addr + 30'h00000010);
      end
      fsm_stateReg <= fsm_stateNext;
      case(fsm_stateReg)
        fsm_1_sSync0 : begin
        end
        fsm_1_sSync1 : begin
        end
        fsm_1_sCmd : begin
          if(rxValid) begin
            cmdReg <= rxU;
            argIdx <= 4'b0000;
            flowErr <= 1'b0;
            case(rxU)
              8'h01 : begin
                argCnt <= 4'b1000;
              end
              8'h02 : begin
                argCnt <= 4'b0001;
              end
              8'h03 : begin
                ackOk <= 1'b1;
              end
              default : begin
                ackOk <= 1'b0;
              end
            endcase
          end
        end
        fsm_1_sArgs : begin
          if(rxValid) begin
            acc <= accNext;
            argIdx <= (argIdx + 4'b0001);
            if(when_UartFbLoader_l163) begin
              if(when_UartFbLoader_l164) begin
                if(when_UartFbLoader_l173) begin
                  ackOk <= 1'b0;
                end else begin
                  push_addr <= _zz_push_addr[29:0];
                  remaining <= _zz_remaining;
                  byteIdx <= 4'b0000;
                  crc <= 8'h0;
                end
              end else begin
                showReg <= ctrl_io_read_payload[0];
                ackOk <= 1'b1;
              end
            end
          end
        end
        fsm_1_sData : begin
          if(rxValid) begin
            wacc <= waccNext;
            crc <= (crc ^ ctrl_io_read_payload);
            byteIdx <= (byteIdx + 4'b0001);
            remaining <= (remaining - 32'h00000001);
            if(when_UartFbLoader_l198) begin
              if(push_pending) begin
                flowErr <= 1'b1;
              end
              push_pending <= 1'b1;
              push_word <= waccNext;
            end
          end
        end
        fsm_1_sCrc : begin
          if(rxValid) begin
            ackOk <= ((ctrl_io_read_payload == crc) && (! flowErr));
          end
        end
        fsm_1_sAck : begin
        end
        default : begin
        end
      endcase
    end
  end


endmodule

module FbPainter (
  output reg           io_bus_cmd_valid,
  input  wire          io_bus_cmd_ready,
  output reg           io_bus_cmd_payload_write,
  output reg  [29:0]   io_bus_cmd_payload_addr,
  output reg  [5:0]    io_bus_cmd_payload_bl,
  output reg           io_bus_wr_valid,
  input  wire          io_bus_wr_ready,
  output wire [127:0]  io_bus_wr_payload,
  input  wire          io_bus_rd_valid,
  output wire          io_bus_rd_ready,
  input  wire [127:0]  io_bus_rd_payload,
  input  wire [29:0]   io_base,
  input  wire          io_start,
  output wire          io_busy,
  input  wire          c3_clk0,
  input  wire          c3_rst0
);
  localparam fsm_BOOT = 2'd0;
  localparam fsm_sIdle = 2'd1;
  localparam fsm_sData = 2'd2;
  localparam fsm_sCmd = 2'd3;

  wire       [9:0]    _zz_xBase;
  wire       [95:0]   _zz_wordData_32;
  wire       [79:0]   _zz_wordData_33;
  wire       [71:0]   _zz_wordData_34;
  wire       [63:0]   _zz_wordData_35;
  wire       [55:0]   _zz_wordData_36;
  wire       [47:0]   _zz_wordData_37;
  wire       [39:0]   _zz_wordData_38;
  wire       [31:0]   _zz_wordData_39;
  wire       [23:0]   _zz_wordData_40;
  wire       [15:0]   _zz_wordData_41;
  wire       [7:0]    _zz_wordData_42;
  wire                _zz_wordData_43;
  wire                _zz_wordData_44;
  wire                _zz_wordData_45;
  wire                _zz_wordData_46;
  wire       [8:0]    _zz_wordData_47;
  wire                _zz_wordData_48;
  wire                _zz_wordData_49;
  wire       [10:0]   _zz_wordData_50;
  wire                _zz_wordData_51;
  wire       [10:0]   _zz_wordData_52;
  wire       [10:0]   _zz_wordData_53;
  wire       [7:0]    _zz_wordData_54;
  wire       [7:0]    _zz_wordData_55;
  wire       [2:0]    _zz_wordData_56;
  wire       [2:0]    _zz_wordData_57;
  wire       [0:0]    _zz_wordData_58;
  wire       [0:0]    _zz_wordData_59;
  wire       [7:0]    _zz_wordData_60;
  wire                _zz_wordData_61;
  wire                _zz_wordData_62;
  wire                _zz_wordData_63;
  wire                _zz_wordData_64;
  wire       [8:0]    _zz_wordData_65;
  wire                _zz_wordData_66;
  wire                _zz_wordData_67;
  wire       [10:0]   _zz_wordData_68;
  wire                _zz_wordData_69;
  wire       [10:0]   _zz_wordData_70;
  wire       [10:0]   _zz_wordData_71;
  wire       [7:0]    _zz_wordData_72;
  wire       [7:0]    _zz_wordData_73;
  wire       [2:0]    _zz_wordData_74;
  wire       [2:0]    _zz_wordData_75;
  wire       [0:0]    _zz_wordData_76;
  wire       [0:0]    _zz_wordData_77;
  wire       [7:0]    _zz_wordData_78;
  wire                _zz_wordData_79;
  wire                _zz_wordData_80;
  wire                _zz_wordData_81;
  wire       [10:0]   _zz_wordData_82;
  wire       [10:0]   _zz_wordData_83;
  wire                _zz_wordData_84;
  wire       [8:0]    _zz_wordData_85;
  wire                _zz_wordData_86;
  wire       [10:0]   _zz_wordData_87;
  wire       [10:0]   _zz_wordData_88;
  wire       [10:0]   _zz_wordData_89;
  wire       [10:0]   _zz_wordData_90;
  wire       [7:0]    _zz_wordData_91;
  wire       [7:0]    _zz_wordData_92;
  wire       [5:0]    _zz_wordData_93;
  wire       [2:0]    _zz_wordData_94;
  wire       [2:0]    _zz_wordData_95;
  wire       [1:0]    _zz_wordData_96;
  wire                _zz_wordData_97;
  wire                _zz_wordData_98;
  wire       [7:0]    _zz_wordData_99;
  wire                _zz_wordData_100;
  wire                _zz_wordData_101;
  wire                _zz_wordData_102;
  wire                _zz_wordData_103;
  wire                _zz_wordData_104;
  wire       [8:0]    _zz_wordData_105;
  wire                _zz_wordData_106;
  wire                _zz_wordData_107;
  wire                _zz_wordData_108;
  wire       [10:0]   _zz_wordData_109;
  wire                _zz_wordData_110;
  wire       [10:0]   _zz_wordData_111;
  wire       [10:0]   _zz_wordData_112;
  wire       [7:0]    _zz_wordData_113;
  wire       [7:0]    _zz_wordData_114;
  wire       [5:0]    _zz_wordData_115;
  wire       [2:0]    _zz_wordData_116;
  wire       [2:0]    _zz_wordData_117;
  wire       [1:0]    _zz_wordData_118;
  wire       [0:0]    _zz_wordData_119;
  wire       [0:0]    _zz_wordData_120;
  wire       [7:0]    _zz_wordData_121;
  wire                _zz_wordData_122;
  wire                _zz_wordData_123;
  wire                _zz_wordData_124;
  wire                _zz_wordData_125;
  wire       [10:0]   _zz_wordData_126;
  wire       [10:0]   _zz_wordData_127;
  wire                _zz_wordData_128;
  wire                _zz_wordData_129;
  wire       [8:0]    _zz_wordData_130;
  wire                _zz_wordData_131;
  wire                _zz_wordData_132;
  wire       [10:0]   _zz_wordData_133;
  wire                _zz_wordData_134;
  wire       [10:0]   _zz_wordData_135;
  wire       [10:0]   _zz_wordData_136;
  wire       [10:0]   _zz_wordData_137;
  wire       [7:0]    _zz_wordData_138;
  wire       [7:0]    _zz_wordData_139;
  wire       [5:0]    _zz_wordData_140;
  wire       [2:0]    _zz_wordData_141;
  wire       [2:0]    _zz_wordData_142;
  wire       [2:0]    _zz_wordData_143;
  wire       [2:0]    _zz_wordData_144;
  wire       [1:0]    _zz_wordData_145;
  wire       [0:0]    _zz_wordData_146;
  wire                _zz_wordData_147;
  wire       [0:0]    _zz_wordData_148;
  wire                _zz_wordData_149;
  wire       [7:0]    _zz_wordData_150;
  wire                _zz_wordData_151;
  wire                _zz_wordData_152;
  wire                _zz_wordData_153;
  wire                _zz_wordData_154;
  wire                _zz_wordData_155;
  wire                _zz_wordData_156;
  wire                _zz_wordData_157;
  wire       [8:0]    _zz_wordData_158;
  wire                _zz_wordData_159;
  wire       [8:0]    _zz_wordData_160;
  wire                _zz_wordData_161;
  wire                _zz_wordData_162;
  wire       [10:0]   _zz_wordData_163;
  wire                _zz_wordData_164;
  wire       [10:0]   _zz_wordData_165;
  wire       [10:0]   _zz_wordData_166;
  wire       [10:0]   _zz_wordData_167;
  wire       [7:0]    _zz_wordData_168;
  wire       [7:0]    _zz_wordData_169;
  wire       [5:0]    _zz_wordData_170;
  wire       [2:0]    _zz_wordData_171;
  wire       [2:0]    _zz_wordData_172;
  wire       [2:0]    _zz_wordData_173;
  wire       [2:0]    _zz_wordData_174;
  wire       [1:0]    _zz_wordData_175;
  wire       [0:0]    _zz_wordData_176;
  wire                _zz_wordData_177;
  wire       [0:0]    _zz_wordData_178;
  wire                _zz_wordData_179;
  wire       [7:0]    _zz_wordData_180;
  wire                _zz_wordData_181;
  wire                _zz_wordData_182;
  wire                _zz_wordData_183;
  wire                _zz_wordData_184;
  wire                _zz_wordData_185;
  wire       [10:0]   _zz_wordData_186;
  wire                _zz_wordData_187;
  wire       [10:0]   _zz_wordData_188;
  wire                _zz_wordData_189;
  wire       [8:0]    _zz_wordData_190;
  wire                _zz_wordData_191;
  wire       [8:0]    _zz_wordData_192;
  wire                _zz_wordData_193;
  wire                _zz_wordData_194;
  wire       [10:0]   _zz_wordData_195;
  wire                _zz_wordData_196;
  wire       [10:0]   _zz_wordData_197;
  wire       [10:0]   _zz_wordData_198;
  wire       [10:0]   _zz_wordData_199;
  wire       [7:0]    _zz_wordData_200;
  wire       [7:0]    _zz_wordData_201;
  wire       [5:0]    _zz_wordData_202;
  wire       [2:0]    _zz_wordData_203;
  wire       [2:0]    _zz_wordData_204;
  wire       [2:0]    _zz_wordData_205;
  wire       [2:0]    _zz_wordData_206;
  wire       [1:0]    _zz_wordData_207;
  wire       [0:0]    _zz_wordData_208;
  wire                _zz_wordData_209;
  wire       [0:0]    _zz_wordData_210;
  wire                _zz_wordData_211;
  wire       [7:0]    _zz_wordData_212;
  wire                _zz_wordData_213;
  wire                _zz_wordData_214;
  wire                _zz_wordData_215;
  wire                _zz_wordData_216;
  wire                _zz_wordData_217;
  wire       [10:0]   _zz_wordData_218;
  wire                _zz_wordData_219;
  wire       [10:0]   _zz_wordData_220;
  wire                _zz_wordData_221;
  wire       [8:0]    _zz_wordData_222;
  wire                _zz_wordData_223;
  wire       [8:0]    _zz_wordData_224;
  wire                _zz_wordData_225;
  wire                _zz_wordData_226;
  wire       [10:0]   _zz_wordData_227;
  wire                _zz_wordData_228;
  wire       [10:0]   _zz_wordData_229;
  wire       [10:0]   _zz_wordData_230;
  wire       [10:0]   _zz_wordData_231;
  wire       [7:0]    _zz_wordData_232;
  wire       [7:0]    _zz_wordData_233;
  wire       [5:0]    _zz_wordData_234;
  wire       [2:0]    _zz_wordData_235;
  wire       [2:0]    _zz_wordData_236;
  wire       [2:0]    _zz_wordData_237;
  wire       [2:0]    _zz_wordData_238;
  wire       [1:0]    _zz_wordData_239;
  wire       [0:0]    _zz_wordData_240;
  wire                _zz_wordData_241;
  wire       [0:0]    _zz_wordData_242;
  wire                _zz_wordData_243;
  wire       [7:0]    _zz_wordData_244;
  wire                _zz_wordData_245;
  wire                _zz_wordData_246;
  wire                _zz_wordData_247;
  wire                _zz_wordData_248;
  wire                _zz_wordData_249;
  wire       [10:0]   _zz_wordData_250;
  wire                _zz_wordData_251;
  wire       [10:0]   _zz_wordData_252;
  wire                _zz_wordData_253;
  wire       [8:0]    _zz_wordData_254;
  wire                _zz_wordData_255;
  wire       [8:0]    _zz_wordData_256;
  wire                _zz_wordData_257;
  wire                _zz_wordData_258;
  wire       [10:0]   _zz_wordData_259;
  wire                _zz_wordData_260;
  wire       [10:0]   _zz_wordData_261;
  wire       [10:0]   _zz_wordData_262;
  wire       [10:0]   _zz_wordData_263;
  wire       [7:0]    _zz_wordData_264;
  wire       [7:0]    _zz_wordData_265;
  wire       [5:0]    _zz_wordData_266;
  wire       [2:0]    _zz_wordData_267;
  wire       [2:0]    _zz_wordData_268;
  wire       [2:0]    _zz_wordData_269;
  wire       [2:0]    _zz_wordData_270;
  wire       [1:0]    _zz_wordData_271;
  wire       [0:0]    _zz_wordData_272;
  wire                _zz_wordData_273;
  wire       [0:0]    _zz_wordData_274;
  wire                _zz_wordData_275;
  wire       [7:0]    _zz_wordData_276;
  wire                _zz_wordData_277;
  wire                _zz_wordData_278;
  wire                _zz_wordData_279;
  wire                _zz_wordData_280;
  wire                _zz_wordData_281;
  wire       [10:0]   _zz_wordData_282;
  wire                _zz_wordData_283;
  wire       [10:0]   _zz_wordData_284;
  wire                _zz_wordData_285;
  wire       [8:0]    _zz_wordData_286;
  wire                _zz_wordData_287;
  wire       [8:0]    _zz_wordData_288;
  wire                _zz_wordData_289;
  wire                _zz_wordData_290;
  wire       [10:0]   _zz_wordData_291;
  wire                _zz_wordData_292;
  wire       [10:0]   _zz_wordData_293;
  wire       [10:0]   _zz_wordData_294;
  wire       [10:0]   _zz_wordData_295;
  wire       [7:0]    _zz_wordData_296;
  wire       [7:0]    _zz_wordData_297;
  wire       [5:0]    _zz_wordData_298;
  wire       [2:0]    _zz_wordData_299;
  wire       [2:0]    _zz_wordData_300;
  wire       [2:0]    _zz_wordData_301;
  wire       [2:0]    _zz_wordData_302;
  wire       [1:0]    _zz_wordData_303;
  wire       [0:0]    _zz_wordData_304;
  wire                _zz_wordData_305;
  wire       [0:0]    _zz_wordData_306;
  wire                _zz_wordData_307;
  wire       [7:0]    _zz_wordData_308;
  wire                _zz_wordData_309;
  wire                _zz_wordData_310;
  wire                _zz_wordData_311;
  wire                _zz_wordData_312;
  wire                _zz_wordData_313;
  wire       [10:0]   _zz_wordData_314;
  wire                _zz_wordData_315;
  wire       [10:0]   _zz_wordData_316;
  wire                _zz_wordData_317;
  wire       [8:0]    _zz_wordData_318;
  wire                _zz_wordData_319;
  wire       [8:0]    _zz_wordData_320;
  wire                _zz_wordData_321;
  wire                _zz_wordData_322;
  wire       [10:0]   _zz_wordData_323;
  wire                _zz_wordData_324;
  wire       [10:0]   _zz_wordData_325;
  wire       [10:0]   _zz_wordData_326;
  wire       [10:0]   _zz_wordData_327;
  wire       [7:0]    _zz_wordData_328;
  wire       [7:0]    _zz_wordData_329;
  wire       [5:0]    _zz_wordData_330;
  wire       [2:0]    _zz_wordData_331;
  wire       [2:0]    _zz_wordData_332;
  wire       [2:0]    _zz_wordData_333;
  wire       [2:0]    _zz_wordData_334;
  wire       [1:0]    _zz_wordData_335;
  wire       [0:0]    _zz_wordData_336;
  wire                _zz_wordData_337;
  wire       [0:0]    _zz_wordData_338;
  wire                _zz_wordData_339;
  wire                _zz_wordData_340;
  wire                _zz_wordData_341;
  wire                _zz_wordData_342;
  wire                _zz_wordData_343;
  wire                _zz_wordData_344;
  wire       [10:0]   _zz_wordData_345;
  wire                _zz_wordData_346;
  wire       [10:0]   _zz_wordData_347;
  wire                _zz_wordData_348;
  wire       [8:0]    _zz_wordData_349;
  wire                _zz_wordData_350;
  wire       [8:0]    _zz_wordData_351;
  wire                _zz_wordData_352;
  wire                _zz_wordData_353;
  wire       [10:0]   _zz_wordData_354;
  wire                _zz_wordData_355;
  wire       [10:0]   _zz_wordData_356;
  wire       [10:0]   _zz_wordData_357;
  wire       [10:0]   _zz_wordData_358;
  wire       [7:0]    _zz_wordData_359;
  wire       [7:0]    _zz_wordData_360;
  wire       [5:0]    _zz_wordData_361;
  wire       [2:0]    _zz_wordData_362;
  wire       [2:0]    _zz_wordData_363;
  wire       [2:0]    _zz_wordData_364;
  wire       [2:0]    _zz_wordData_365;
  wire       [1:0]    _zz_wordData_366;
  wire       [0:0]    _zz_wordData_367;
  wire                _zz_wordData_368;
  wire       [0:0]    _zz_wordData_369;
  wire                _zz_wordData_370;
  wire       [7:0]    _zz_wordData_371;
  wire                _zz_wordData_372;
  wire                _zz_wordData_373;
  wire                _zz_wordData_374;
  wire                _zz_wordData_375;
  wire       [10:0]   _zz_wordData_376;
  wire                _zz_wordData_377;
  wire       [10:0]   _zz_wordData_378;
  wire                _zz_wordData_379;
  wire       [8:0]    _zz_wordData_380;
  wire                _zz_wordData_381;
  wire       [8:0]    _zz_wordData_382;
  wire                _zz_wordData_383;
  wire                _zz_wordData_384;
  wire       [10:0]   _zz_wordData_385;
  wire                _zz_wordData_386;
  wire       [10:0]   _zz_wordData_387;
  wire       [10:0]   _zz_wordData_388;
  wire       [10:0]   _zz_wordData_389;
  wire       [5:0]    _zz_wordData_390;
  wire       [2:0]    _zz_wordData_391;
  wire       [2:0]    _zz_wordData_392;
  wire       [2:0]    _zz_wordData_393;
  wire       [2:0]    _zz_wordData_394;
  wire       [1:0]    _zz_wordData_395;
  wire       [0:0]    _zz_wordData_396;
  wire                _zz_wordData_397;
  wire       [0:0]    _zz_wordData_398;
  wire                _zz_wordData_399;
  wire                _zz_wordData_400;
  wire                _zz_wordData_401;
  wire                _zz_wordData_402;
  wire                _zz_wordData_403;
  wire       [10:0]   _zz_wordData_404;
  wire                _zz_wordData_405;
  wire       [10:0]   _zz_wordData_406;
  wire                _zz_wordData_407;
  wire       [8:0]    _zz_wordData_408;
  wire                _zz_wordData_409;
  wire       [8:0]    _zz_wordData_410;
  wire                _zz_wordData_411;
  wire       [10:0]   _zz_wordData_412;
  wire                _zz_wordData_413;
  wire       [10:0]   _zz_wordData_414;
  wire       [10:0]   _zz_wordData_415;
  wire       [10:0]   _zz_wordData_416;
  wire       [7:0]    _zz_wordData_417;
  wire       [7:0]    _zz_wordData_418;
  wire       [2:0]    _zz_wordData_419;
  wire       [2:0]    _zz_wordData_420;
  wire       [2:0]    _zz_wordData_421;
  wire       [2:0]    _zz_wordData_422;
  wire       [0:0]    _zz_wordData_423;
  wire                _zz_wordData_424;
  wire       [0:0]    _zz_wordData_425;
  wire                _zz_wordData_426;
  wire                _zz_wordData_427;
  wire                _zz_wordData_428;
  wire                _zz_wordData_429;
  wire       [10:0]   _zz_wordData_430;
  wire                _zz_wordData_431;
  wire       [10:0]   _zz_wordData_432;
  wire                _zz_wordData_433;
  wire       [8:0]    _zz_wordData_434;
  wire       [8:0]    _zz_wordData_435;
  wire                _zz_wordData_436;
  wire       [10:0]   _zz_wordData_437;
  wire       [10:0]   _zz_wordData_438;
  wire       [10:0]   _zz_wordData_439;
  wire       [10:0]   _zz_wordData_440;
  wire       [5:0]    _zz_wordData_441;
  wire       [2:0]    _zz_wordData_442;
  wire       [2:0]    _zz_wordData_443;
  wire       [1:0]    _zz_wordData_444;
  wire                _zz_wordData_445;
  wire                _zz_wordData_446;
  wire                _zz_wordData_447;
  wire                _zz_wordData_448;
  wire       [10:0]   _zz_wordData_449;
  wire                _zz_wordData_450;
  wire       [10:0]   _zz_wordData_451;
  wire       [8:0]    _zz_wordData_452;
  wire                _zz_wordData_453;
  wire                _zz_wordData_454;
  wire       [10:0]   _zz_wordData_455;
  wire                _zz_wordData_456;
  wire       [10:0]   _zz_wordData_457;
  wire       [10:0]   _zz_wordData_458;
  wire       [2:0]    _zz_wordData_459;
  wire       [2:0]    _zz_wordData_460;
  wire       [0:0]    _zz_wordData_461;
  wire       [0:0]    _zz_wordData_462;
  reg        [8:0]    line;
  reg        [5:0]    word;
  reg        [29:0]   addr;
  wire       [10:0]   xBase;
  wire       [10:0]   _zz_wordData;
  wire       [8:0]    _zz_wordData_1;
  wire       [10:0]   _zz_wordData_2;
  wire       [8:0]    _zz_wordData_3;
  wire       [10:0]   _zz_wordData_4;
  wire       [8:0]    _zz_wordData_5;
  wire       [10:0]   _zz_wordData_6;
  wire       [8:0]    _zz_wordData_7;
  wire       [10:0]   _zz_wordData_8;
  wire       [8:0]    _zz_wordData_9;
  wire       [10:0]   _zz_wordData_10;
  wire       [8:0]    _zz_wordData_11;
  wire       [10:0]   _zz_wordData_12;
  wire       [8:0]    _zz_wordData_13;
  wire       [10:0]   _zz_wordData_14;
  wire       [8:0]    _zz_wordData_15;
  wire       [10:0]   _zz_wordData_16;
  wire       [8:0]    _zz_wordData_17;
  wire       [10:0]   _zz_wordData_18;
  wire       [8:0]    _zz_wordData_19;
  wire       [10:0]   _zz_wordData_20;
  wire       [8:0]    _zz_wordData_21;
  wire       [10:0]   _zz_wordData_22;
  wire       [8:0]    _zz_wordData_23;
  wire       [10:0]   _zz_wordData_24;
  wire       [8:0]    _zz_wordData_25;
  wire       [10:0]   _zz_wordData_26;
  wire       [8:0]    _zz_wordData_27;
  wire       [10:0]   _zz_wordData_28;
  wire       [8:0]    _zz_wordData_29;
  wire       [10:0]   _zz_wordData_30;
  wire       [8:0]    _zz_wordData_31;
  wire       [127:0]  wordData;
  wire                fsm_wantExit;
  reg                 fsm_wantStart;
  wire                fsm_wantKill;
  reg        [1:0]    fsm_stateReg;
  reg        [1:0]    fsm_stateNext;
  wire                when_FbPainter_l80;
  wire                when_FbPainter_l93;
  wire                fsm_onExit_BOOT;
  wire                fsm_onExit_sIdle;
  wire                fsm_onExit_sData;
  wire                fsm_onExit_sCmd;
  wire                fsm_onEntry_BOOT;
  wire                fsm_onEntry_sIdle;
  wire                fsm_onEntry_sData;
  wire                fsm_onEntry_sCmd;
  `ifndef SYNTHESIS
  reg [39:0] fsm_stateReg_string;
  reg [39:0] fsm_stateNext_string;
  `endif


  assign _zz_xBase = ({4'd0,word} <<< 3'd4);
  assign _zz_wordData_50 = {2'd0, _zz_wordData_31};
  assign _zz_wordData_52 = (_zz_wordData_30 + _zz_wordData_53);
  assign _zz_wordData_53 = {2'd0, _zz_wordData_31};
  assign _zz_wordData_68 = {2'd0, _zz_wordData_29};
  assign _zz_wordData_70 = (_zz_wordData_28 + _zz_wordData_71);
  assign _zz_wordData_71 = {2'd0, _zz_wordData_29};
  assign _zz_wordData_87 = {2'd0, _zz_wordData_27};
  assign _zz_wordData_88 = (_zz_wordData_26 + _zz_wordData_89);
  assign _zz_wordData_89 = {2'd0, _zz_wordData_27};
  assign _zz_wordData_109 = {2'd0, _zz_wordData_25};
  assign _zz_wordData_111 = (_zz_wordData_24 + _zz_wordData_112);
  assign _zz_wordData_112 = {2'd0, _zz_wordData_25};
  assign _zz_wordData_133 = {2'd0, _zz_wordData_23};
  assign _zz_wordData_135 = (_zz_wordData_22 + _zz_wordData_136);
  assign _zz_wordData_136 = {2'd0, _zz_wordData_23};
  assign _zz_wordData_163 = {2'd0, _zz_wordData_21};
  assign _zz_wordData_165 = (_zz_wordData_20 + _zz_wordData_166);
  assign _zz_wordData_166 = {2'd0, _zz_wordData_21};
  assign _zz_wordData_195 = {2'd0, _zz_wordData_19};
  assign _zz_wordData_197 = (_zz_wordData_18 + _zz_wordData_198);
  assign _zz_wordData_198 = {2'd0, _zz_wordData_19};
  assign _zz_wordData_227 = {2'd0, _zz_wordData_17};
  assign _zz_wordData_229 = (_zz_wordData_16 + _zz_wordData_230);
  assign _zz_wordData_230 = {2'd0, _zz_wordData_17};
  assign _zz_wordData_259 = {2'd0, _zz_wordData_15};
  assign _zz_wordData_261 = (_zz_wordData_14 + _zz_wordData_262);
  assign _zz_wordData_262 = {2'd0, _zz_wordData_15};
  assign _zz_wordData_291 = {2'd0, _zz_wordData_13};
  assign _zz_wordData_293 = (_zz_wordData_12 + _zz_wordData_294);
  assign _zz_wordData_294 = {2'd0, _zz_wordData_13};
  assign _zz_wordData_323 = {2'd0, _zz_wordData_11};
  assign _zz_wordData_325 = (_zz_wordData_10 + _zz_wordData_326);
  assign _zz_wordData_326 = {2'd0, _zz_wordData_11};
  assign _zz_wordData_354 = {2'd0, _zz_wordData_9};
  assign _zz_wordData_356 = (_zz_wordData_8 + _zz_wordData_357);
  assign _zz_wordData_357 = {2'd0, _zz_wordData_9};
  assign _zz_wordData_385 = {2'd0, _zz_wordData_7};
  assign _zz_wordData_387 = (_zz_wordData_6 + _zz_wordData_388);
  assign _zz_wordData_388 = {2'd0, _zz_wordData_7};
  assign _zz_wordData_412 = {2'd0, _zz_wordData_5};
  assign _zz_wordData_414 = (_zz_wordData_4 + _zz_wordData_415);
  assign _zz_wordData_415 = {2'd0, _zz_wordData_5};
  assign _zz_wordData_437 = {2'd0, _zz_wordData_3};
  assign _zz_wordData_438 = (_zz_wordData_2 + _zz_wordData_439);
  assign _zz_wordData_439 = {2'd0, _zz_wordData_3};
  assign _zz_wordData_455 = {2'd0, _zz_wordData_1};
  assign _zz_wordData_457 = (_zz_wordData + _zz_wordData_458);
  assign _zz_wordData_458 = {2'd0, _zz_wordData_1};
  assign _zz_wordData_32 = {{_zz_wordData_33,_zz_wordData_308},(_zz_wordData_340 ? _zz_wordData_359 : _zz_wordData_360)};
  assign _zz_wordData_371 = ((_zz_wordData_372 || _zz_wordData_383) ? 8'hff : {_zz_wordData_390,_zz_wordData_395});
  assign _zz_wordData_400 = ((_zz_wordData_401 || _zz_wordData_409) || (_zz_wordData_411 || _zz_wordData_413));
  assign _zz_wordData_417 = 8'hff;
  assign _zz_wordData_418 = {{_zz_wordData_419,_zz_wordData_421},{_zz_wordData_423,_zz_wordData_425}};
  assign _zz_wordData_427 = ((_zz_wordData_428 || _zz_wordData_433) || (_zz_wordData_3 == _zz_wordData_435));
  assign _zz_wordData_436 = ((_zz_wordData_2 == _zz_wordData_437) || (_zz_wordData_438 == _zz_wordData_440));
  assign _zz_wordData_441 = {_zz_wordData_442,_zz_wordData_443};
  assign _zz_wordData_444 = {_zz_wordData_445,_zz_wordData_446};
  assign _zz_wordData_447 = ((_zz_wordData_448 || _zz_wordData_450) || (_zz_wordData_1 == _zz_wordData_452));
  assign _zz_wordData_453 = (_zz_wordData_1 == 9'h1df);
  assign _zz_wordData_454 = (_zz_wordData == _zz_wordData_455);
  assign _zz_wordData_456 = (_zz_wordData_457 == 11'h27f);
  assign _zz_wordData_459 = _zz_wordData[5 : 3];
  assign _zz_wordData_460 = _zz_wordData_1[5 : 3];
  assign _zz_wordData_461 = _zz_wordData[6];
  assign _zz_wordData_462 = _zz_wordData_1[6];
  assign _zz_wordData_33 = {_zz_wordData_34,_zz_wordData_276};
  assign _zz_wordData_308 = (_zz_wordData_309 ? _zz_wordData_328 : _zz_wordData_329);
  assign _zz_wordData_340 = (_zz_wordData_341 || _zz_wordData_352);
  assign _zz_wordData_359 = 8'hff;
  assign _zz_wordData_360 = {_zz_wordData_361,_zz_wordData_366};
  assign _zz_wordData_372 = (_zz_wordData_373 || _zz_wordData_381);
  assign _zz_wordData_383 = (_zz_wordData_384 || _zz_wordData_386);
  assign _zz_wordData_390 = {_zz_wordData_391,_zz_wordData_393};
  assign _zz_wordData_395 = {_zz_wordData_396,_zz_wordData_398};
  assign _zz_wordData_401 = (_zz_wordData_402 || _zz_wordData_407);
  assign _zz_wordData_409 = (_zz_wordData_5 == _zz_wordData_410);
  assign _zz_wordData_411 = (_zz_wordData_4 == _zz_wordData_412);
  assign _zz_wordData_413 = (_zz_wordData_414 == _zz_wordData_416);
  assign _zz_wordData_419 = _zz_wordData_420;
  assign _zz_wordData_421 = _zz_wordData_422;
  assign _zz_wordData_423 = _zz_wordData_424;
  assign _zz_wordData_425 = _zz_wordData_426;
  assign _zz_wordData_428 = (_zz_wordData_429 || _zz_wordData_431);
  assign _zz_wordData_433 = (_zz_wordData_3 == _zz_wordData_434);
  assign _zz_wordData_435 = 9'h1df;
  assign _zz_wordData_440 = 11'h27f;
  assign _zz_wordData_442 = _zz_wordData_2[5 : 3];
  assign _zz_wordData_443 = _zz_wordData_3[5 : 3];
  assign _zz_wordData_445 = _zz_wordData_2[6];
  assign _zz_wordData_446 = _zz_wordData_3[6];
  assign _zz_wordData_448 = (_zz_wordData == _zz_wordData_449);
  assign _zz_wordData_450 = (_zz_wordData == _zz_wordData_451);
  assign _zz_wordData_452 = 9'h0;
  assign _zz_wordData_34 = {_zz_wordData_35,_zz_wordData_244};
  assign _zz_wordData_276 = (_zz_wordData_277 ? _zz_wordData_296 : _zz_wordData_297);
  assign _zz_wordData_309 = (_zz_wordData_310 || _zz_wordData_321);
  assign _zz_wordData_328 = 8'hff;
  assign _zz_wordData_329 = {_zz_wordData_330,_zz_wordData_335};
  assign _zz_wordData_341 = (_zz_wordData_342 || _zz_wordData_350);
  assign _zz_wordData_352 = (_zz_wordData_353 || _zz_wordData_355);
  assign _zz_wordData_361 = {_zz_wordData_362,_zz_wordData_364};
  assign _zz_wordData_366 = {_zz_wordData_367,_zz_wordData_369};
  assign _zz_wordData_373 = (_zz_wordData_374 || _zz_wordData_379);
  assign _zz_wordData_381 = (_zz_wordData_7 == _zz_wordData_382);
  assign _zz_wordData_384 = (_zz_wordData_6 == _zz_wordData_385);
  assign _zz_wordData_386 = (_zz_wordData_387 == _zz_wordData_389);
  assign _zz_wordData_391 = _zz_wordData_392;
  assign _zz_wordData_393 = _zz_wordData_394;
  assign _zz_wordData_396 = _zz_wordData_397;
  assign _zz_wordData_398 = _zz_wordData_399;
  assign _zz_wordData_402 = (_zz_wordData_403 || _zz_wordData_405);
  assign _zz_wordData_407 = (_zz_wordData_5 == _zz_wordData_408);
  assign _zz_wordData_410 = 9'h1df;
  assign _zz_wordData_416 = 11'h27f;
  assign _zz_wordData_420 = _zz_wordData_4[5 : 3];
  assign _zz_wordData_422 = _zz_wordData_5[5 : 3];
  assign _zz_wordData_424 = _zz_wordData_4[6];
  assign _zz_wordData_426 = _zz_wordData_5[6];
  assign _zz_wordData_429 = (_zz_wordData_2 == _zz_wordData_430);
  assign _zz_wordData_431 = (_zz_wordData_2 == _zz_wordData_432);
  assign _zz_wordData_434 = 9'h0;
  assign _zz_wordData_449 = 11'h0;
  assign _zz_wordData_451 = 11'h27f;
  assign _zz_wordData_35 = {_zz_wordData_36,_zz_wordData_212};
  assign _zz_wordData_244 = (_zz_wordData_245 ? _zz_wordData_264 : _zz_wordData_265);
  assign _zz_wordData_277 = (_zz_wordData_278 || _zz_wordData_289);
  assign _zz_wordData_296 = 8'hff;
  assign _zz_wordData_297 = {_zz_wordData_298,_zz_wordData_303};
  assign _zz_wordData_310 = (_zz_wordData_311 || _zz_wordData_319);
  assign _zz_wordData_321 = (_zz_wordData_322 || _zz_wordData_324);
  assign _zz_wordData_330 = {_zz_wordData_331,_zz_wordData_333};
  assign _zz_wordData_335 = {_zz_wordData_336,_zz_wordData_338};
  assign _zz_wordData_342 = (_zz_wordData_343 || _zz_wordData_348);
  assign _zz_wordData_350 = (_zz_wordData_9 == _zz_wordData_351);
  assign _zz_wordData_353 = (_zz_wordData_8 == _zz_wordData_354);
  assign _zz_wordData_355 = (_zz_wordData_356 == _zz_wordData_358);
  assign _zz_wordData_362 = _zz_wordData_363;
  assign _zz_wordData_364 = _zz_wordData_365;
  assign _zz_wordData_367 = _zz_wordData_368;
  assign _zz_wordData_369 = _zz_wordData_370;
  assign _zz_wordData_374 = (_zz_wordData_375 || _zz_wordData_377);
  assign _zz_wordData_379 = (_zz_wordData_7 == _zz_wordData_380);
  assign _zz_wordData_382 = 9'h1df;
  assign _zz_wordData_389 = 11'h27f;
  assign _zz_wordData_392 = _zz_wordData_6[5 : 3];
  assign _zz_wordData_394 = _zz_wordData_7[5 : 3];
  assign _zz_wordData_397 = _zz_wordData_6[6];
  assign _zz_wordData_399 = _zz_wordData_7[6];
  assign _zz_wordData_403 = (_zz_wordData_4 == _zz_wordData_404);
  assign _zz_wordData_405 = (_zz_wordData_4 == _zz_wordData_406);
  assign _zz_wordData_408 = 9'h0;
  assign _zz_wordData_430 = 11'h0;
  assign _zz_wordData_432 = 11'h27f;
  assign _zz_wordData_36 = {_zz_wordData_37,_zz_wordData_180};
  assign _zz_wordData_212 = (_zz_wordData_213 ? _zz_wordData_232 : _zz_wordData_233);
  assign _zz_wordData_245 = (_zz_wordData_246 || _zz_wordData_257);
  assign _zz_wordData_264 = 8'hff;
  assign _zz_wordData_265 = {_zz_wordData_266,_zz_wordData_271};
  assign _zz_wordData_278 = (_zz_wordData_279 || _zz_wordData_287);
  assign _zz_wordData_289 = (_zz_wordData_290 || _zz_wordData_292);
  assign _zz_wordData_298 = {_zz_wordData_299,_zz_wordData_301};
  assign _zz_wordData_303 = {_zz_wordData_304,_zz_wordData_306};
  assign _zz_wordData_311 = (_zz_wordData_312 || _zz_wordData_317);
  assign _zz_wordData_319 = (_zz_wordData_11 == _zz_wordData_320);
  assign _zz_wordData_322 = (_zz_wordData_10 == _zz_wordData_323);
  assign _zz_wordData_324 = (_zz_wordData_325 == _zz_wordData_327);
  assign _zz_wordData_331 = _zz_wordData_332;
  assign _zz_wordData_333 = _zz_wordData_334;
  assign _zz_wordData_336 = _zz_wordData_337;
  assign _zz_wordData_338 = _zz_wordData_339;
  assign _zz_wordData_343 = (_zz_wordData_344 || _zz_wordData_346);
  assign _zz_wordData_348 = (_zz_wordData_9 == _zz_wordData_349);
  assign _zz_wordData_351 = 9'h1df;
  assign _zz_wordData_358 = 11'h27f;
  assign _zz_wordData_363 = _zz_wordData_8[5 : 3];
  assign _zz_wordData_365 = _zz_wordData_9[5 : 3];
  assign _zz_wordData_368 = _zz_wordData_8[6];
  assign _zz_wordData_370 = _zz_wordData_9[6];
  assign _zz_wordData_375 = (_zz_wordData_6 == _zz_wordData_376);
  assign _zz_wordData_377 = (_zz_wordData_6 == _zz_wordData_378);
  assign _zz_wordData_380 = 9'h0;
  assign _zz_wordData_404 = 11'h0;
  assign _zz_wordData_406 = 11'h27f;
  assign _zz_wordData_37 = {_zz_wordData_38,_zz_wordData_150};
  assign _zz_wordData_180 = (_zz_wordData_181 ? _zz_wordData_200 : _zz_wordData_201);
  assign _zz_wordData_213 = (_zz_wordData_214 || _zz_wordData_225);
  assign _zz_wordData_232 = 8'hff;
  assign _zz_wordData_233 = {_zz_wordData_234,_zz_wordData_239};
  assign _zz_wordData_246 = (_zz_wordData_247 || _zz_wordData_255);
  assign _zz_wordData_257 = (_zz_wordData_258 || _zz_wordData_260);
  assign _zz_wordData_266 = {_zz_wordData_267,_zz_wordData_269};
  assign _zz_wordData_271 = {_zz_wordData_272,_zz_wordData_274};
  assign _zz_wordData_279 = (_zz_wordData_280 || _zz_wordData_285);
  assign _zz_wordData_287 = (_zz_wordData_13 == _zz_wordData_288);
  assign _zz_wordData_290 = (_zz_wordData_12 == _zz_wordData_291);
  assign _zz_wordData_292 = (_zz_wordData_293 == _zz_wordData_295);
  assign _zz_wordData_299 = _zz_wordData_300;
  assign _zz_wordData_301 = _zz_wordData_302;
  assign _zz_wordData_304 = _zz_wordData_305;
  assign _zz_wordData_306 = _zz_wordData_307;
  assign _zz_wordData_312 = (_zz_wordData_313 || _zz_wordData_315);
  assign _zz_wordData_317 = (_zz_wordData_11 == _zz_wordData_318);
  assign _zz_wordData_320 = 9'h1df;
  assign _zz_wordData_327 = 11'h27f;
  assign _zz_wordData_332 = _zz_wordData_10[5 : 3];
  assign _zz_wordData_334 = _zz_wordData_11[5 : 3];
  assign _zz_wordData_337 = _zz_wordData_10[6];
  assign _zz_wordData_339 = _zz_wordData_11[6];
  assign _zz_wordData_344 = (_zz_wordData_8 == _zz_wordData_345);
  assign _zz_wordData_346 = (_zz_wordData_8 == _zz_wordData_347);
  assign _zz_wordData_349 = 9'h0;
  assign _zz_wordData_376 = 11'h0;
  assign _zz_wordData_378 = 11'h27f;
  assign _zz_wordData_38 = {_zz_wordData_39,_zz_wordData_121};
  assign _zz_wordData_150 = (_zz_wordData_151 ? _zz_wordData_168 : _zz_wordData_169);
  assign _zz_wordData_181 = (_zz_wordData_182 || _zz_wordData_193);
  assign _zz_wordData_200 = 8'hff;
  assign _zz_wordData_201 = {_zz_wordData_202,_zz_wordData_207};
  assign _zz_wordData_214 = (_zz_wordData_215 || _zz_wordData_223);
  assign _zz_wordData_225 = (_zz_wordData_226 || _zz_wordData_228);
  assign _zz_wordData_234 = {_zz_wordData_235,_zz_wordData_237};
  assign _zz_wordData_239 = {_zz_wordData_240,_zz_wordData_242};
  assign _zz_wordData_247 = (_zz_wordData_248 || _zz_wordData_253);
  assign _zz_wordData_255 = (_zz_wordData_15 == _zz_wordData_256);
  assign _zz_wordData_258 = (_zz_wordData_14 == _zz_wordData_259);
  assign _zz_wordData_260 = (_zz_wordData_261 == _zz_wordData_263);
  assign _zz_wordData_267 = _zz_wordData_268;
  assign _zz_wordData_269 = _zz_wordData_270;
  assign _zz_wordData_272 = _zz_wordData_273;
  assign _zz_wordData_274 = _zz_wordData_275;
  assign _zz_wordData_280 = (_zz_wordData_281 || _zz_wordData_283);
  assign _zz_wordData_285 = (_zz_wordData_13 == _zz_wordData_286);
  assign _zz_wordData_288 = 9'h1df;
  assign _zz_wordData_295 = 11'h27f;
  assign _zz_wordData_300 = _zz_wordData_12[5 : 3];
  assign _zz_wordData_302 = _zz_wordData_13[5 : 3];
  assign _zz_wordData_305 = _zz_wordData_12[6];
  assign _zz_wordData_307 = _zz_wordData_13[6];
  assign _zz_wordData_313 = (_zz_wordData_10 == _zz_wordData_314);
  assign _zz_wordData_315 = (_zz_wordData_10 == _zz_wordData_316);
  assign _zz_wordData_318 = 9'h0;
  assign _zz_wordData_345 = 11'h0;
  assign _zz_wordData_347 = 11'h27f;
  assign _zz_wordData_39 = {_zz_wordData_40,_zz_wordData_99};
  assign _zz_wordData_121 = (_zz_wordData_122 ? _zz_wordData_138 : _zz_wordData_139);
  assign _zz_wordData_151 = (_zz_wordData_152 || _zz_wordData_161);
  assign _zz_wordData_168 = 8'hff;
  assign _zz_wordData_169 = {_zz_wordData_170,_zz_wordData_175};
  assign _zz_wordData_182 = (_zz_wordData_183 || _zz_wordData_191);
  assign _zz_wordData_193 = (_zz_wordData_194 || _zz_wordData_196);
  assign _zz_wordData_202 = {_zz_wordData_203,_zz_wordData_205};
  assign _zz_wordData_207 = {_zz_wordData_208,_zz_wordData_210};
  assign _zz_wordData_215 = (_zz_wordData_216 || _zz_wordData_221);
  assign _zz_wordData_223 = (_zz_wordData_17 == _zz_wordData_224);
  assign _zz_wordData_226 = (_zz_wordData_16 == _zz_wordData_227);
  assign _zz_wordData_228 = (_zz_wordData_229 == _zz_wordData_231);
  assign _zz_wordData_235 = _zz_wordData_236;
  assign _zz_wordData_237 = _zz_wordData_238;
  assign _zz_wordData_240 = _zz_wordData_241;
  assign _zz_wordData_242 = _zz_wordData_243;
  assign _zz_wordData_248 = (_zz_wordData_249 || _zz_wordData_251);
  assign _zz_wordData_253 = (_zz_wordData_15 == _zz_wordData_254);
  assign _zz_wordData_256 = 9'h1df;
  assign _zz_wordData_263 = 11'h27f;
  assign _zz_wordData_268 = _zz_wordData_14[5 : 3];
  assign _zz_wordData_270 = _zz_wordData_15[5 : 3];
  assign _zz_wordData_273 = _zz_wordData_14[6];
  assign _zz_wordData_275 = _zz_wordData_15[6];
  assign _zz_wordData_281 = (_zz_wordData_12 == _zz_wordData_282);
  assign _zz_wordData_283 = (_zz_wordData_12 == _zz_wordData_284);
  assign _zz_wordData_286 = 9'h0;
  assign _zz_wordData_314 = 11'h0;
  assign _zz_wordData_316 = 11'h27f;
  assign _zz_wordData_40 = {_zz_wordData_41,_zz_wordData_78};
  assign _zz_wordData_99 = (_zz_wordData_100 ? _zz_wordData_113 : _zz_wordData_114);
  assign _zz_wordData_122 = (_zz_wordData_123 || _zz_wordData_131);
  assign _zz_wordData_138 = 8'hff;
  assign _zz_wordData_139 = {_zz_wordData_140,_zz_wordData_145};
  assign _zz_wordData_152 = (_zz_wordData_153 || _zz_wordData_159);
  assign _zz_wordData_161 = (_zz_wordData_162 || _zz_wordData_164);
  assign _zz_wordData_170 = {_zz_wordData_171,_zz_wordData_173};
  assign _zz_wordData_175 = {_zz_wordData_176,_zz_wordData_178};
  assign _zz_wordData_183 = (_zz_wordData_184 || _zz_wordData_189);
  assign _zz_wordData_191 = (_zz_wordData_19 == _zz_wordData_192);
  assign _zz_wordData_194 = (_zz_wordData_18 == _zz_wordData_195);
  assign _zz_wordData_196 = (_zz_wordData_197 == _zz_wordData_199);
  assign _zz_wordData_203 = _zz_wordData_204;
  assign _zz_wordData_205 = _zz_wordData_206;
  assign _zz_wordData_208 = _zz_wordData_209;
  assign _zz_wordData_210 = _zz_wordData_211;
  assign _zz_wordData_216 = (_zz_wordData_217 || _zz_wordData_219);
  assign _zz_wordData_221 = (_zz_wordData_17 == _zz_wordData_222);
  assign _zz_wordData_224 = 9'h1df;
  assign _zz_wordData_231 = 11'h27f;
  assign _zz_wordData_236 = _zz_wordData_16[5 : 3];
  assign _zz_wordData_238 = _zz_wordData_17[5 : 3];
  assign _zz_wordData_241 = _zz_wordData_16[6];
  assign _zz_wordData_243 = _zz_wordData_17[6];
  assign _zz_wordData_249 = (_zz_wordData_14 == _zz_wordData_250);
  assign _zz_wordData_251 = (_zz_wordData_14 == _zz_wordData_252);
  assign _zz_wordData_254 = 9'h0;
  assign _zz_wordData_282 = 11'h0;
  assign _zz_wordData_284 = 11'h27f;
  assign _zz_wordData_41 = {_zz_wordData_42,_zz_wordData_60};
  assign _zz_wordData_78 = (_zz_wordData_79 ? _zz_wordData_91 : _zz_wordData_92);
  assign _zz_wordData_100 = (_zz_wordData_101 || _zz_wordData_107);
  assign _zz_wordData_113 = 8'hff;
  assign _zz_wordData_114 = {_zz_wordData_115,_zz_wordData_118};
  assign _zz_wordData_123 = (_zz_wordData_124 || _zz_wordData_129);
  assign _zz_wordData_131 = (_zz_wordData_132 || _zz_wordData_134);
  assign _zz_wordData_140 = {_zz_wordData_141,_zz_wordData_143};
  assign _zz_wordData_145 = {_zz_wordData_146,_zz_wordData_148};
  assign _zz_wordData_153 = (_zz_wordData_154 || _zz_wordData_157);
  assign _zz_wordData_159 = (_zz_wordData_21 == _zz_wordData_160);
  assign _zz_wordData_162 = (_zz_wordData_20 == _zz_wordData_163);
  assign _zz_wordData_164 = (_zz_wordData_165 == _zz_wordData_167);
  assign _zz_wordData_171 = _zz_wordData_172;
  assign _zz_wordData_173 = _zz_wordData_174;
  assign _zz_wordData_176 = _zz_wordData_177;
  assign _zz_wordData_178 = _zz_wordData_179;
  assign _zz_wordData_184 = (_zz_wordData_185 || _zz_wordData_187);
  assign _zz_wordData_189 = (_zz_wordData_19 == _zz_wordData_190);
  assign _zz_wordData_192 = 9'h1df;
  assign _zz_wordData_199 = 11'h27f;
  assign _zz_wordData_204 = _zz_wordData_18[5 : 3];
  assign _zz_wordData_206 = _zz_wordData_19[5 : 3];
  assign _zz_wordData_209 = _zz_wordData_18[6];
  assign _zz_wordData_211 = _zz_wordData_19[6];
  assign _zz_wordData_217 = (_zz_wordData_16 == _zz_wordData_218);
  assign _zz_wordData_219 = (_zz_wordData_16 == _zz_wordData_220);
  assign _zz_wordData_222 = 9'h0;
  assign _zz_wordData_250 = 11'h0;
  assign _zz_wordData_252 = 11'h27f;
  assign _zz_wordData_42 = (_zz_wordData_43 ? _zz_wordData_54 : _zz_wordData_55);
  assign _zz_wordData_60 = (_zz_wordData_61 ? _zz_wordData_72 : _zz_wordData_73);
  assign _zz_wordData_79 = (_zz_wordData_80 || _zz_wordData_86);
  assign _zz_wordData_91 = 8'hff;
  assign _zz_wordData_92 = {_zz_wordData_93,_zz_wordData_96};
  assign _zz_wordData_101 = (_zz_wordData_102 || _zz_wordData_106);
  assign _zz_wordData_107 = (_zz_wordData_108 || _zz_wordData_110);
  assign _zz_wordData_115 = {_zz_wordData_116,_zz_wordData_117};
  assign _zz_wordData_118 = {_zz_wordData_119,_zz_wordData_120};
  assign _zz_wordData_124 = (_zz_wordData_125 || _zz_wordData_128);
  assign _zz_wordData_129 = (_zz_wordData_23 == _zz_wordData_130);
  assign _zz_wordData_132 = (_zz_wordData_22 == _zz_wordData_133);
  assign _zz_wordData_134 = (_zz_wordData_135 == _zz_wordData_137);
  assign _zz_wordData_141 = _zz_wordData_142;
  assign _zz_wordData_143 = _zz_wordData_144;
  assign _zz_wordData_146 = _zz_wordData_147;
  assign _zz_wordData_148 = _zz_wordData_149;
  assign _zz_wordData_154 = (_zz_wordData_155 || _zz_wordData_156);
  assign _zz_wordData_157 = (_zz_wordData_21 == _zz_wordData_158);
  assign _zz_wordData_160 = 9'h1df;
  assign _zz_wordData_167 = 11'h27f;
  assign _zz_wordData_172 = _zz_wordData_20[5 : 3];
  assign _zz_wordData_174 = _zz_wordData_21[5 : 3];
  assign _zz_wordData_177 = _zz_wordData_20[6];
  assign _zz_wordData_179 = _zz_wordData_21[6];
  assign _zz_wordData_185 = (_zz_wordData_18 == _zz_wordData_186);
  assign _zz_wordData_187 = (_zz_wordData_18 == _zz_wordData_188);
  assign _zz_wordData_190 = 9'h0;
  assign _zz_wordData_218 = 11'h0;
  assign _zz_wordData_220 = 11'h27f;
  assign _zz_wordData_43 = ((_zz_wordData_44 || _zz_wordData_48) || (_zz_wordData_49 || _zz_wordData_51));
  assign _zz_wordData_54 = 8'hff;
  assign _zz_wordData_55 = {{_zz_wordData_56,_zz_wordData_57},{_zz_wordData_58,_zz_wordData_59}};
  assign _zz_wordData_61 = ((_zz_wordData_62 || _zz_wordData_66) || (_zz_wordData_67 || _zz_wordData_69));
  assign _zz_wordData_72 = 8'hff;
  assign _zz_wordData_73 = {{_zz_wordData_74,_zz_wordData_75},{_zz_wordData_76,_zz_wordData_77}};
  assign _zz_wordData_80 = ((_zz_wordData_81 || _zz_wordData_84) || (_zz_wordData_27 == _zz_wordData_85));
  assign _zz_wordData_86 = ((_zz_wordData_26 == _zz_wordData_87) || (_zz_wordData_88 == _zz_wordData_90));
  assign _zz_wordData_93 = {_zz_wordData_94,_zz_wordData_95};
  assign _zz_wordData_96 = {_zz_wordData_97,_zz_wordData_98};
  assign _zz_wordData_102 = ((_zz_wordData_103 || _zz_wordData_104) || (_zz_wordData_25 == _zz_wordData_105));
  assign _zz_wordData_106 = (_zz_wordData_25 == 9'h1df);
  assign _zz_wordData_108 = (_zz_wordData_24 == _zz_wordData_109);
  assign _zz_wordData_110 = (_zz_wordData_111 == 11'h27f);
  assign _zz_wordData_116 = _zz_wordData_24[5 : 3];
  assign _zz_wordData_117 = _zz_wordData_25[5 : 3];
  assign _zz_wordData_119 = _zz_wordData_24[6];
  assign _zz_wordData_120 = _zz_wordData_25[6];
  assign _zz_wordData_125 = ((_zz_wordData_22 == _zz_wordData_126) || (_zz_wordData_22 == _zz_wordData_127));
  assign _zz_wordData_128 = (_zz_wordData_23 == 9'h0);
  assign _zz_wordData_130 = 9'h1df;
  assign _zz_wordData_137 = 11'h27f;
  assign _zz_wordData_142 = _zz_wordData_22[5 : 3];
  assign _zz_wordData_144 = _zz_wordData_23[5 : 3];
  assign _zz_wordData_147 = _zz_wordData_22[6];
  assign _zz_wordData_149 = _zz_wordData_23[6];
  assign _zz_wordData_155 = (_zz_wordData_20 == 11'h0);
  assign _zz_wordData_156 = (_zz_wordData_20 == 11'h27f);
  assign _zz_wordData_158 = 9'h0;
  assign _zz_wordData_186 = 11'h0;
  assign _zz_wordData_188 = 11'h27f;
  assign _zz_wordData_44 = ((_zz_wordData_45 || _zz_wordData_46) || (_zz_wordData_31 == _zz_wordData_47));
  assign _zz_wordData_48 = (_zz_wordData_31 == 9'h1df);
  assign _zz_wordData_49 = (_zz_wordData_30 == _zz_wordData_50);
  assign _zz_wordData_51 = (_zz_wordData_52 == 11'h27f);
  assign _zz_wordData_56 = _zz_wordData_30[5 : 3];
  assign _zz_wordData_57 = _zz_wordData_31[5 : 3];
  assign _zz_wordData_58 = _zz_wordData_30[6];
  assign _zz_wordData_59 = _zz_wordData_31[6];
  assign _zz_wordData_62 = ((_zz_wordData_63 || _zz_wordData_64) || (_zz_wordData_29 == _zz_wordData_65));
  assign _zz_wordData_66 = (_zz_wordData_29 == 9'h1df);
  assign _zz_wordData_67 = (_zz_wordData_28 == _zz_wordData_68);
  assign _zz_wordData_69 = (_zz_wordData_70 == 11'h27f);
  assign _zz_wordData_74 = _zz_wordData_28[5 : 3];
  assign _zz_wordData_75 = _zz_wordData_29[5 : 3];
  assign _zz_wordData_76 = _zz_wordData_28[6];
  assign _zz_wordData_77 = _zz_wordData_29[6];
  assign _zz_wordData_81 = ((_zz_wordData_26 == _zz_wordData_82) || (_zz_wordData_26 == _zz_wordData_83));
  assign _zz_wordData_84 = (_zz_wordData_27 == 9'h0);
  assign _zz_wordData_85 = 9'h1df;
  assign _zz_wordData_90 = 11'h27f;
  assign _zz_wordData_94 = _zz_wordData_26[5 : 3];
  assign _zz_wordData_95 = _zz_wordData_27[5 : 3];
  assign _zz_wordData_97 = _zz_wordData_26[6];
  assign _zz_wordData_98 = _zz_wordData_27[6];
  assign _zz_wordData_103 = (_zz_wordData_24 == 11'h0);
  assign _zz_wordData_104 = (_zz_wordData_24 == 11'h27f);
  assign _zz_wordData_105 = 9'h0;
  assign _zz_wordData_126 = 11'h0;
  assign _zz_wordData_127 = 11'h27f;
  assign _zz_wordData_45 = (_zz_wordData_30 == 11'h0);
  assign _zz_wordData_46 = (_zz_wordData_30 == 11'h27f);
  assign _zz_wordData_47 = 9'h0;
  assign _zz_wordData_63 = (_zz_wordData_28 == 11'h0);
  assign _zz_wordData_64 = (_zz_wordData_28 == 11'h27f);
  assign _zz_wordData_65 = 9'h0;
  assign _zz_wordData_82 = 11'h0;
  assign _zz_wordData_83 = 11'h27f;
  `ifndef SYNTHESIS
  always @(*) begin
    case(fsm_stateReg)
      fsm_BOOT : fsm_stateReg_string = "BOOT ";
      fsm_sIdle : fsm_stateReg_string = "sIdle";
      fsm_sData : fsm_stateReg_string = "sData";
      fsm_sCmd : fsm_stateReg_string = "sCmd ";
      default : fsm_stateReg_string = "?????";
    endcase
  end
  always @(*) begin
    case(fsm_stateNext)
      fsm_BOOT : fsm_stateNext_string = "BOOT ";
      fsm_sIdle : fsm_stateNext_string = "sIdle";
      fsm_sData : fsm_stateNext_string = "sData";
      fsm_sCmd : fsm_stateNext_string = "sCmd ";
      default : fsm_stateNext_string = "?????";
    endcase
  end
  `endif

  assign io_bus_rd_ready = 1'b0;
  assign xBase = {1'd0, _zz_xBase};
  assign _zz_wordData = (xBase + 11'h0);
  assign _zz_wordData_1 = line;
  assign _zz_wordData_2 = (xBase + 11'h001);
  assign _zz_wordData_3 = line;
  assign _zz_wordData_4 = (xBase + 11'h002);
  assign _zz_wordData_5 = line;
  assign _zz_wordData_6 = (xBase + 11'h003);
  assign _zz_wordData_7 = line;
  assign _zz_wordData_8 = (xBase + 11'h004);
  assign _zz_wordData_9 = line;
  assign _zz_wordData_10 = (xBase + 11'h005);
  assign _zz_wordData_11 = line;
  assign _zz_wordData_12 = (xBase + 11'h006);
  assign _zz_wordData_13 = line;
  assign _zz_wordData_14 = (xBase + 11'h007);
  assign _zz_wordData_15 = line;
  assign _zz_wordData_16 = (xBase + 11'h008);
  assign _zz_wordData_17 = line;
  assign _zz_wordData_18 = (xBase + 11'h009);
  assign _zz_wordData_19 = line;
  assign _zz_wordData_20 = (xBase + 11'h00a);
  assign _zz_wordData_21 = line;
  assign _zz_wordData_22 = (xBase + 11'h00b);
  assign _zz_wordData_23 = line;
  assign _zz_wordData_24 = (xBase + 11'h00c);
  assign _zz_wordData_25 = line;
  assign _zz_wordData_26 = (xBase + 11'h00d);
  assign _zz_wordData_27 = line;
  assign _zz_wordData_28 = (xBase + 11'h00e);
  assign _zz_wordData_29 = line;
  assign _zz_wordData_30 = (xBase + 11'h00f);
  assign _zz_wordData_31 = line;
  assign wordData = {{{{_zz_wordData_32,_zz_wordData_371},(_zz_wordData_400 ? _zz_wordData_417 : _zz_wordData_418)},((_zz_wordData_427 || _zz_wordData_436) ? 8'hff : {_zz_wordData_441,_zz_wordData_444})},(((_zz_wordData_447 || _zz_wordData_453) || (_zz_wordData_454 || _zz_wordData_456)) ? 8'hff : {{_zz_wordData_459,_zz_wordData_460},{_zz_wordData_461,_zz_wordData_462}})};
  always @(*) begin
    io_bus_wr_valid = 1'b0;
    case(fsm_stateReg)
      fsm_sIdle : begin
      end
      fsm_sData : begin
        io_bus_wr_valid = 1'b1;
      end
      fsm_sCmd : begin
      end
      default : begin
      end
    endcase
  end

  assign io_bus_wr_payload = wordData;
  always @(*) begin
    io_bus_cmd_valid = 1'b0;
    case(fsm_stateReg)
      fsm_sIdle : begin
      end
      fsm_sData : begin
      end
      fsm_sCmd : begin
        io_bus_cmd_valid = 1'b1;
      end
      default : begin
      end
    endcase
  end

  always @(*) begin
    io_bus_cmd_payload_write = 1'bx;
    case(fsm_stateReg)
      fsm_sIdle : begin
      end
      fsm_sData : begin
      end
      fsm_sCmd : begin
        io_bus_cmd_payload_write = 1'b1;
      end
      default : begin
      end
    endcase
  end

  always @(*) begin
    io_bus_cmd_payload_addr = 30'bxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx;
    case(fsm_stateReg)
      fsm_sIdle : begin
      end
      fsm_sData : begin
      end
      fsm_sCmd : begin
        io_bus_cmd_payload_addr = addr;
      end
      default : begin
      end
    endcase
  end

  always @(*) begin
    io_bus_cmd_payload_bl = 6'bxxxxxx;
    case(fsm_stateReg)
      fsm_sIdle : begin
      end
      fsm_sData : begin
      end
      fsm_sCmd : begin
        io_bus_cmd_payload_bl = 6'h27;
      end
      default : begin
      end
    endcase
  end

  assign fsm_wantExit = 1'b0;
  always @(*) begin
    fsm_wantStart = 1'b0;
    case(fsm_stateReg)
      fsm_sIdle : begin
      end
      fsm_sData : begin
      end
      fsm_sCmd : begin
      end
      default : begin
        fsm_wantStart = 1'b1;
      end
    endcase
  end

  assign fsm_wantKill = 1'b0;
  assign io_busy = (! (fsm_stateReg == fsm_sIdle));
  always @(*) begin
    fsm_stateNext = fsm_stateReg;
    case(fsm_stateReg)
      fsm_sIdle : begin
        if(io_start) begin
          fsm_stateNext = fsm_sData;
        end
      end
      fsm_sData : begin
        if(io_bus_wr_ready) begin
          if(when_FbPainter_l80) begin
            fsm_stateNext = fsm_sCmd;
          end
        end
      end
      fsm_sCmd : begin
        if(io_bus_cmd_ready) begin
          if(when_FbPainter_l93) begin
            fsm_stateNext = fsm_sIdle;
          end else begin
            fsm_stateNext = fsm_sData;
          end
        end
      end
      default : begin
      end
    endcase
    if(fsm_wantStart) begin
      fsm_stateNext = fsm_sIdle;
    end
    if(fsm_wantKill) begin
      fsm_stateNext = fsm_BOOT;
    end
  end

  assign when_FbPainter_l80 = (word == 6'h27);
  assign when_FbPainter_l93 = (line == 9'h1df);
  assign fsm_onExit_BOOT = ((fsm_stateNext != fsm_BOOT) && (fsm_stateReg == fsm_BOOT));
  assign fsm_onExit_sIdle = ((fsm_stateNext != fsm_sIdle) && (fsm_stateReg == fsm_sIdle));
  assign fsm_onExit_sData = ((fsm_stateNext != fsm_sData) && (fsm_stateReg == fsm_sData));
  assign fsm_onExit_sCmd = ((fsm_stateNext != fsm_sCmd) && (fsm_stateReg == fsm_sCmd));
  assign fsm_onEntry_BOOT = ((fsm_stateNext == fsm_BOOT) && (fsm_stateReg != fsm_BOOT));
  assign fsm_onEntry_sIdle = ((fsm_stateNext == fsm_sIdle) && (fsm_stateReg != fsm_sIdle));
  assign fsm_onEntry_sData = ((fsm_stateNext == fsm_sData) && (fsm_stateReg != fsm_sData));
  assign fsm_onEntry_sCmd = ((fsm_stateNext == fsm_sCmd) && (fsm_stateReg != fsm_sCmd));
  always @(posedge c3_clk0) begin
    if(c3_rst0) begin
      line <= 9'h0;
      word <= 6'h0;
      addr <= 30'h0;
      fsm_stateReg <= fsm_BOOT;
    end else begin
      fsm_stateReg <= fsm_stateNext;
      case(fsm_stateReg)
        fsm_sIdle : begin
          if(io_start) begin
            line <= 9'h0;
            word <= 6'h0;
            addr <= io_base;
          end
        end
        fsm_sData : begin
          if(io_bus_wr_ready) begin
            word <= (word + 6'h01);
          end
        end
        fsm_sCmd : begin
          if(io_bus_cmd_ready) begin
            word <= 6'h0;
            addr <= (addr + 30'h00000400);
            line <= (line + 9'h001);
          end
        end
        default : begin
        end
      endcase
    end
  end


endmodule

module FbLineReader (
  output reg           io_bus_cmd_valid,
  input  wire          io_bus_cmd_ready,
  output reg           io_bus_cmd_payload_write,
  output reg  [29:0]   io_bus_cmd_payload_addr,
  output reg  [5:0]    io_bus_cmd_payload_bl,
  output wire          io_bus_wr_valid,
  input  wire          io_bus_wr_ready,
  output wire [127:0]  io_bus_wr_payload,
  input  wire          io_bus_rd_valid,
  output reg           io_bus_rd_ready,
  input  wire [127:0]  io_bus_rd_payload,
  input  wire [29:0]   io_base,
  input  wire          io_frameStart,
  input  wire          io_enable,
  output wire          io_pixels_valid,
  input  wire          io_pixels_ready,
  output wire [2:0]    io_pixels_payload_r,
  output wire [2:0]    io_pixels_payload_g,
  output wire [1:0]    io_pixels_payload_b,
  output wire          io_underflow,
  input  wire          c3_clk0,
  input  wire          c3_rst0
);
  localparam fetch_fsm_BOOT = 2'd0;
  localparam fetch_fsm_sIdle = 2'd1;
  localparam fetch_fsm_sCmd = 2'd2;
  localparam fetch_fsm_sData = 2'd3;

  reg        [127:0]  lineMem_spinal_port0;
  wire       [9:0]    _zz_xNext;
  wire       [6:0]    _zz_rdAddr;
  wire       [5:0]    _zz_rdAddr_1;
  wire                _zz_lineMem_port;
  wire                _zz_rdWord;
  wire       [6:0]    _zz_px;
  reg                 _zz_io_pixels_valid;
  wire       [0:0]    _zz_io_pixels_valid_1;
  wire       [29:0]   _zz_fetch_addr;
  wire       [18:0]   _zz_fetch_addr_1;
  reg                 _zz_1;
  reg        [9:0]    x;
  reg        [8:0]    y;
  wire                io_pixels_fire;
  wire                lineEnd;
  reg        [9:0]    xNext;
  reg        [8:0]    yNext;
  wire       [8:0]    fbLine;
  wire       [8:0]    fbLineNext;
  wire                bank;
  wire                bankNext;
  wire       [6:0]    rdAddr;
  wire       [127:0]  rdWord;
  wire       [3:0]    byteSel;
  wire       [7:0]    px;
  reg                 bankReady_0;
  reg                 bankReady_1;
  reg        [8:0]    fetch_line;
  reg                 fetch_bankId;
  reg        [1:0]    fetch_credit;
  reg        [29:0]   fetch_addr;
  reg        [1:0]    fetch_burst;
  reg        [4:0]    fetch_word;
  reg        [6:0]    fetch_wrPtr;
  reg                 fetch_resync;
  reg                 fetch_start;
  reg                 fetch_creditReset;
  wire                fetch_fsm_wantExit;
  reg                 fetch_fsm_wantStart;
  wire                fetch_fsm_wantKill;
  wire                when_FbLineReader_l207;
  wire                when_FbLineReader_l209;
  reg        [1:0]    fetch_fsm_stateReg;
  reg        [1:0]    fetch_fsm_stateNext;
  wire                when_FbLineReader_l142;
  wire       [1:0]    _zz_4;
  wire                when_FbLineReader_l153;
  wire                when_FbLineReader_l178;
  wire                when_FbLineReader_l182;
  wire       [1:0]    _zz_5;
  wire                fetch_fsm_onExit_BOOT;
  wire                fetch_fsm_onExit_sIdle;
  wire                fetch_fsm_onExit_sCmd;
  wire                fetch_fsm_onExit_sData;
  wire                fetch_fsm_onEntry_BOOT;
  wire                fetch_fsm_onEntry_sIdle;
  wire                fetch_fsm_onEntry_sCmd;
  wire                fetch_fsm_onEntry_sData;
  `ifndef SYNTHESIS
  reg [39:0] fetch_fsm_stateReg_string;
  reg [39:0] fetch_fsm_stateNext_string;
  `endif

  reg [127:0] lineMem [0:79];

  assign _zz_xNext = (x + 10'h001);
  assign _zz_rdAddr_1 = (xNext >>> 3'd4);
  assign _zz_rdAddr = {1'd0, _zz_rdAddr_1};
  assign _zz_px = ({3'd0,byteSel} <<< 2'd3);
  assign _zz_fetch_addr_1 = ({10'd0,fetch_line} <<< 4'd10);
  assign _zz_fetch_addr = {11'd0, _zz_fetch_addr_1};
  assign _zz_rdWord = 1'b1;
  assign _zz_io_pixels_valid_1 = bank;
  always @(posedge c3_clk0) begin
    if(_zz_rdWord) begin
      lineMem_spinal_port0 <= lineMem[rdAddr];
    end
  end

  always @(posedge c3_clk0) begin
    if(_zz_1) begin
      lineMem[fetch_wrPtr] <= io_bus_rd_payload;
    end
  end

  always @(*) begin
    case(_zz_io_pixels_valid_1)
      1'b0 : _zz_io_pixels_valid = bankReady_0;
      default : _zz_io_pixels_valid = bankReady_1;
    endcase
  end

  `ifndef SYNTHESIS
  always @(*) begin
    case(fetch_fsm_stateReg)
      fetch_fsm_BOOT : fetch_fsm_stateReg_string = "BOOT ";
      fetch_fsm_sIdle : fetch_fsm_stateReg_string = "sIdle";
      fetch_fsm_sCmd : fetch_fsm_stateReg_string = "sCmd ";
      fetch_fsm_sData : fetch_fsm_stateReg_string = "sData";
      default : fetch_fsm_stateReg_string = "?????";
    endcase
  end
  always @(*) begin
    case(fetch_fsm_stateNext)
      fetch_fsm_BOOT : fetch_fsm_stateNext_string = "BOOT ";
      fetch_fsm_sIdle : fetch_fsm_stateNext_string = "sIdle";
      fetch_fsm_sCmd : fetch_fsm_stateNext_string = "sCmd ";
      fetch_fsm_sData : fetch_fsm_stateNext_string = "sData";
      default : fetch_fsm_stateNext_string = "?????";
    endcase
  end
  `endif

  always @(*) begin
    _zz_1 = 1'b0;
    case(fetch_fsm_stateReg)
      fetch_fsm_sIdle : begin
      end
      fetch_fsm_sCmd : begin
      end
      fetch_fsm_sData : begin
        if(io_bus_rd_valid) begin
          _zz_1 = 1'b1;
        end
      end
      default : begin
      end
    endcase
  end

  assign io_bus_wr_valid = 1'b0;
  assign io_bus_wr_payload = 128'h0;
  always @(*) begin
    io_bus_cmd_valid = 1'b0;
    case(fetch_fsm_stateReg)
      fetch_fsm_sIdle : begin
      end
      fetch_fsm_sCmd : begin
        io_bus_cmd_valid = 1'b1;
      end
      fetch_fsm_sData : begin
      end
      default : begin
      end
    endcase
  end

  always @(*) begin
    io_bus_cmd_payload_write = 1'bx;
    case(fetch_fsm_stateReg)
      fetch_fsm_sIdle : begin
      end
      fetch_fsm_sCmd : begin
        io_bus_cmd_payload_write = 1'b0;
      end
      fetch_fsm_sData : begin
      end
      default : begin
      end
    endcase
  end

  always @(*) begin
    io_bus_cmd_payload_addr = 30'bxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx;
    case(fetch_fsm_stateReg)
      fetch_fsm_sIdle : begin
      end
      fetch_fsm_sCmd : begin
        io_bus_cmd_payload_addr = fetch_addr;
      end
      fetch_fsm_sData : begin
      end
      default : begin
      end
    endcase
  end

  always @(*) begin
    io_bus_cmd_payload_bl = 6'bxxxxxx;
    case(fetch_fsm_stateReg)
      fetch_fsm_sIdle : begin
      end
      fetch_fsm_sCmd : begin
        io_bus_cmd_payload_bl = 6'h13;
      end
      fetch_fsm_sData : begin
      end
      default : begin
      end
    endcase
  end

  always @(*) begin
    io_bus_rd_ready = 1'b0;
    case(fetch_fsm_stateReg)
      fetch_fsm_sIdle : begin
      end
      fetch_fsm_sCmd : begin
      end
      fetch_fsm_sData : begin
        io_bus_rd_ready = 1'b1;
      end
      default : begin
      end
    endcase
  end

  assign io_pixels_fire = (io_pixels_valid && io_pixels_ready);
  assign lineEnd = (io_pixels_fire && (x == 10'h27f));
  always @(*) begin
    xNext = x;
    if(io_pixels_fire) begin
      xNext = ((x == 10'h27f) ? 10'h0 : _zz_xNext);
    end
    if(io_frameStart) begin
      xNext = 10'h0;
    end
  end

  always @(*) begin
    yNext = y;
    if(lineEnd) begin
      yNext = (y + 9'h001);
    end
    if(io_frameStart) begin
      yNext = 9'h0;
    end
  end

  assign fbLine = y;
  assign fbLineNext = yNext;
  assign bank = fbLine[0];
  assign bankNext = fbLineNext[0];
  assign rdAddr = (_zz_rdAddr + (bankNext ? 7'h28 : 7'h0));
  assign rdWord = lineMem_spinal_port0;
  assign byteSel = x[3 : 0];
  assign px = rdWord[_zz_px +: 8];
  assign io_pixels_payload_r = px[7 : 5];
  assign io_pixels_payload_g = px[4 : 2];
  assign io_pixels_payload_b = px[1 : 0];
  assign io_pixels_valid = _zz_io_pixels_valid;
  assign io_underflow = (io_pixels_ready && (! io_pixels_valid));
  always @(*) begin
    fetch_start = 1'b0;
    case(fetch_fsm_stateReg)
      fetch_fsm_sIdle : begin
        if(!when_FbLineReader_l142) begin
          if(!fetch_resync) begin
            if(when_FbLineReader_l153) begin
              fetch_start = 1'b1;
            end
          end
        end
      end
      fetch_fsm_sCmd : begin
      end
      fetch_fsm_sData : begin
      end
      default : begin
      end
    endcase
  end

  always @(*) begin
    fetch_creditReset = 1'b0;
    case(fetch_fsm_stateReg)
      fetch_fsm_sIdle : begin
        if(!when_FbLineReader_l142) begin
          if(fetch_resync) begin
            fetch_creditReset = 1'b1;
          end
        end
      end
      fetch_fsm_sCmd : begin
      end
      fetch_fsm_sData : begin
      end
      default : begin
      end
    endcase
  end

  assign fetch_fsm_wantExit = 1'b0;
  always @(*) begin
    fetch_fsm_wantStart = 1'b0;
    case(fetch_fsm_stateReg)
      fetch_fsm_sIdle : begin
      end
      fetch_fsm_sCmd : begin
      end
      fetch_fsm_sData : begin
      end
      default : begin
        fetch_fsm_wantStart = 1'b1;
      end
    endcase
  end

  assign fetch_fsm_wantKill = 1'b0;
  assign when_FbLineReader_l207 = ((lineEnd && (! fetch_start)) && (fetch_credit != 2'b10));
  assign when_FbLineReader_l209 = ((! lineEnd) && fetch_start);
  always @(*) begin
    fetch_fsm_stateNext = fetch_fsm_stateReg;
    case(fetch_fsm_stateReg)
      fetch_fsm_sIdle : begin
        if(!when_FbLineReader_l142) begin
          if(!fetch_resync) begin
            if(when_FbLineReader_l153) begin
              fetch_fsm_stateNext = fetch_fsm_sCmd;
            end
          end
        end
      end
      fetch_fsm_sCmd : begin
        if(io_bus_cmd_ready) begin
          fetch_fsm_stateNext = fetch_fsm_sData;
        end
      end
      fetch_fsm_sData : begin
        if(io_bus_rd_valid) begin
          if(when_FbLineReader_l178) begin
            if(when_FbLineReader_l182) begin
              fetch_fsm_stateNext = fetch_fsm_sIdle;
            end else begin
              fetch_fsm_stateNext = fetch_fsm_sCmd;
            end
          end
        end
      end
      default : begin
      end
    endcase
    if(fetch_fsm_wantStart) begin
      fetch_fsm_stateNext = fetch_fsm_sIdle;
    end
    if(fetch_fsm_wantKill) begin
      fetch_fsm_stateNext = fetch_fsm_BOOT;
    end
  end

  assign when_FbLineReader_l142 = (! io_enable);
  assign _zz_4 = ({1'd0,1'b1} <<< fetch_bankId);
  assign when_FbLineReader_l153 = ((fetch_credit != 2'b00) && (fetch_line < 9'h1e0));
  assign when_FbLineReader_l178 = (fetch_word == 5'h13);
  assign when_FbLineReader_l182 = (fetch_burst == 2'b01);
  assign _zz_5 = ({1'd0,1'b1} <<< fetch_bankId);
  assign fetch_fsm_onExit_BOOT = ((fetch_fsm_stateNext != fetch_fsm_BOOT) && (fetch_fsm_stateReg == fetch_fsm_BOOT));
  assign fetch_fsm_onExit_sIdle = ((fetch_fsm_stateNext != fetch_fsm_sIdle) && (fetch_fsm_stateReg == fetch_fsm_sIdle));
  assign fetch_fsm_onExit_sCmd = ((fetch_fsm_stateNext != fetch_fsm_sCmd) && (fetch_fsm_stateReg == fetch_fsm_sCmd));
  assign fetch_fsm_onExit_sData = ((fetch_fsm_stateNext != fetch_fsm_sData) && (fetch_fsm_stateReg == fetch_fsm_sData));
  assign fetch_fsm_onEntry_BOOT = ((fetch_fsm_stateNext == fetch_fsm_BOOT) && (fetch_fsm_stateReg != fetch_fsm_BOOT));
  assign fetch_fsm_onEntry_sIdle = ((fetch_fsm_stateNext == fetch_fsm_sIdle) && (fetch_fsm_stateReg != fetch_fsm_sIdle));
  assign fetch_fsm_onEntry_sCmd = ((fetch_fsm_stateNext == fetch_fsm_sCmd) && (fetch_fsm_stateReg != fetch_fsm_sCmd));
  assign fetch_fsm_onEntry_sData = ((fetch_fsm_stateNext == fetch_fsm_sData) && (fetch_fsm_stateReg != fetch_fsm_sData));
  always @(posedge c3_clk0) begin
    if(c3_rst0) begin
      x <= 10'h0;
      y <= 9'h0;
      bankReady_0 <= 1'b0;
      bankReady_1 <= 1'b0;
      fetch_line <= 9'h0;
      fetch_bankId <= 1'b0;
      fetch_credit <= 2'b00;
      fetch_addr <= 30'h0;
      fetch_burst <= 2'b00;
      fetch_word <= 5'h0;
      fetch_wrPtr <= 7'h0;
      fetch_resync <= 1'b1;
      fetch_fsm_stateReg <= fetch_fsm_BOOT;
    end else begin
      x <= xNext;
      y <= yNext;
      if(io_frameStart) begin
        fetch_resync <= 1'b1;
      end
      if(fetch_creditReset) begin
        fetch_credit <= 2'b10;
      end else begin
        if(when_FbLineReader_l207) begin
          fetch_credit <= (fetch_credit + 2'b01);
        end else begin
          if(when_FbLineReader_l209) begin
            fetch_credit <= (fetch_credit - 2'b01);
          end
        end
      end
      fetch_fsm_stateReg <= fetch_fsm_stateNext;
      case(fetch_fsm_stateReg)
        fetch_fsm_sIdle : begin
          if(when_FbLineReader_l142) begin
            fetch_resync <= 1'b1;
            bankReady_0 <= 1'b0;
            bankReady_1 <= 1'b0;
          end else begin
            if(fetch_resync) begin
              fetch_resync <= 1'b0;
              fetch_line <= 9'h0;
              fetch_bankId <= 1'b0;
            end else begin
              if(when_FbLineReader_l153) begin
                if(_zz_4[0]) begin
                  bankReady_0 <= 1'b0;
                end
                if(_zz_4[1]) begin
                  bankReady_1 <= 1'b0;
                end
                fetch_addr <= (io_base + _zz_fetch_addr);
                fetch_burst <= 2'b00;
                fetch_word <= 5'h0;
                fetch_wrPtr <= (fetch_bankId ? 7'h28 : 7'h0);
              end
            end
          end
        end
        fetch_fsm_sCmd : begin
        end
        fetch_fsm_sData : begin
          if(io_bus_rd_valid) begin
            fetch_wrPtr <= (fetch_wrPtr + 7'h01);
            fetch_word <= (fetch_word + 5'h01);
            if(when_FbLineReader_l178) begin
              fetch_word <= 5'h0;
              fetch_addr <= (fetch_addr + 30'h00000140);
              fetch_burst <= (fetch_burst + 2'b01);
              if(when_FbLineReader_l182) begin
                if(_zz_5[0]) begin
                  bankReady_0 <= 1'b1;
                end
                if(_zz_5[1]) begin
                  bankReady_1 <= 1'b1;
                end
                fetch_bankId <= (! fetch_bankId);
                fetch_line <= (fetch_line + 9'h001);
              end
            end
          end
        end
        default : begin
        end
      endcase
    end
  end


endmodule

module VgaCtrl (
  input  wire          io_softReset,
  input  wire [11:0]   io_timings_h_syncStart,
  input  wire [11:0]   io_timings_h_syncEnd,
  input  wire [11:0]   io_timings_h_colorStart,
  input  wire [11:0]   io_timings_h_colorEnd,
  input  wire          io_timings_h_polarity,
  input  wire [11:0]   io_timings_v_syncStart,
  input  wire [11:0]   io_timings_v_syncEnd,
  input  wire [11:0]   io_timings_v_colorStart,
  input  wire [11:0]   io_timings_v_colorEnd,
  input  wire          io_timings_v_polarity,
  output wire          io_frameStart,
  input  wire          io_pixels_valid,
  output wire          io_pixels_ready,
  input  wire [2:0]    io_pixels_payload_r,
  input  wire [2:0]    io_pixels_payload_g,
  input  wire [1:0]    io_pixels_payload_b,
  output wire          io_vga_vSync,
  output wire          io_vga_hSync,
  output wire          io_vga_colorEn,
  output wire [2:0]    io_vga_color_r,
  output wire [2:0]    io_vga_color_g,
  output wire [1:0]    io_vga_color_b,
  output wire          io_error,
  input  wire          c3_clk0,
  input  wire          c3_rst0
);

  wire                when_VgaCtrl_l288;
  reg        [11:0]   h_counter;
  wire                h_syncStart;
  wire                h_syncEnd;
  wire                h_colorStart;
  wire                h_colorEnd;
  reg                 h_sync;
  reg                 h_colorEn;
  reg        [11:0]   v_counter;
  wire                v_syncStart;
  wire                v_syncEnd;
  wire                v_colorStart;
  wire                v_colorEnd;
  reg                 v_sync;
  reg                 v_colorEn;
  wire                colorEn;

  assign when_VgaCtrl_l288 = 1'b1;
  assign h_syncStart = (h_counter == io_timings_h_syncStart);
  assign h_syncEnd = (h_counter == io_timings_h_syncEnd);
  assign h_colorStart = (h_counter == io_timings_h_colorStart);
  assign h_colorEnd = (h_counter == io_timings_h_colorEnd);
  assign v_syncStart = (v_counter == io_timings_v_syncStart);
  assign v_syncEnd = (v_counter == io_timings_v_syncEnd);
  assign v_colorStart = (v_counter == io_timings_v_colorStart);
  assign v_colorEnd = (v_counter == io_timings_v_colorEnd);
  assign colorEn = (h_colorEn && v_colorEn);
  assign io_pixels_ready = (colorEn || io_softReset);
  assign io_error = (colorEn && (! io_pixels_valid));
  assign io_frameStart = (v_syncStart && h_syncStart);
  assign io_vga_hSync = (h_sync ^ io_timings_h_polarity);
  assign io_vga_vSync = (v_sync ^ io_timings_v_polarity);
  assign io_vga_colorEn = colorEn;
  assign io_vga_color_r = io_pixels_payload_r;
  assign io_vga_color_g = io_pixels_payload_g;
  assign io_vga_color_b = io_pixels_payload_b;
  always @(posedge c3_clk0) begin
    if(c3_rst0) begin
      h_counter <= 12'h0;
      h_sync <= 1'b0;
      h_colorEn <= 1'b0;
      v_counter <= 12'h0;
      v_sync <= 1'b0;
      v_colorEn <= 1'b0;
    end else begin
      if(when_VgaCtrl_l288) begin
        h_counter <= (h_counter + 12'h001);
        if(h_syncEnd) begin
          h_counter <= 12'h0;
        end
      end
      if(h_syncStart) begin
        h_sync <= 1'b1;
      end
      if(h_syncEnd) begin
        h_sync <= 1'b0;
      end
      if(h_colorStart) begin
        h_colorEn <= 1'b1;
      end
      if(h_colorEnd) begin
        h_colorEn <= 1'b0;
      end
      if(io_softReset) begin
        h_counter <= 12'h0;
        h_sync <= 1'b0;
        h_colorEn <= 1'b0;
      end
      if(h_syncEnd) begin
        v_counter <= (v_counter + 12'h001);
        if(v_syncEnd) begin
          v_counter <= 12'h0;
        end
      end
      if(v_syncStart) begin
        v_sync <= 1'b1;
      end
      if(v_syncEnd) begin
        v_sync <= 1'b0;
      end
      if(v_colorStart) begin
        v_colorEn <= 1'b1;
      end
      if(v_colorEnd) begin
        v_colorEn <= 1'b0;
      end
      if(io_softReset) begin
        v_counter <= 12'h0;
        v_sync <= 1'b0;
        v_colorEn <= 1'b0;
      end
    end
  end


endmodule

module BufferCC_1 (
  input  wire          io_dataIn,
  output wire          io_dataOut,
  input  wire          c3_clk0,
  input  wire          c3_rst0
);

  (* async_reg = "true" *) reg                 buffers_0;
  (* async_reg = "true" *) reg                 buffers_1;

  assign io_dataOut = buffers_1;
  always @(posedge c3_clk0) begin
    if(c3_rst0) begin
      buffers_0 <= 1'b0;
      buffers_1 <= 1'b0;
    end else begin
      buffers_0 <= io_dataIn;
      buffers_1 <= buffers_0;
    end
  end


endmodule

//Bcd3_1 replaced by Bcd3

module Bcd3 (
  input  wire          io_inc,
  input  wire          io_clear,
  output wire [3:0]    io_digits_0,
  output wire [3:0]    io_digits_1,
  output wire [3:0]    io_digits_2,
  input  wire          c3_clk0,
  input  wire          c3_rst0
);

  wire       [3:0]    _zz_d_0;
  wire       [3:0]    _zz_d_1;
  wire       [3:0]    _zz_d_2;
  reg        [3:0]    d_0;
  reg        [3:0]    d_1;
  reg        [3:0]    d_2;
  wire                carry_0;
  wire                carry_1;
  wire                carry_2;

  assign _zz_d_0 = (d_0 + 4'b0001);
  assign _zz_d_1 = (d_1 + 4'b0001);
  assign _zz_d_2 = (d_2 + 4'b0001);
  assign carry_0 = (io_inc && (d_0 == 4'b1001));
  assign carry_1 = (carry_0 && (d_1 == 4'b1001));
  assign carry_2 = (carry_1 && (d_2 == 4'b1001));
  assign io_digits_0 = d_0;
  assign io_digits_1 = d_1;
  assign io_digits_2 = d_2;
  always @(posedge c3_clk0) begin
    if(c3_rst0) begin
      d_0 <= 4'b0000;
      d_1 <= 4'b0000;
      d_2 <= 4'b0000;
    end else begin
      if(io_inc) begin
        d_0 <= ((d_0 == 4'b1001) ? 4'b0000 : _zz_d_0);
      end
      if(carry_0) begin
        d_1 <= ((d_1 == 4'b1001) ? 4'b0000 : _zz_d_1);
      end
      if(carry_1) begin
        d_2 <= ((d_2 == 4'b1001) ? 4'b0000 : _zz_d_2);
      end
      if(io_clear) begin
        d_0 <= 4'b0000;
        d_1 <= 4'b0000;
        d_2 <= 4'b0000;
      end
    end
  end


endmodule

module UartCtrl (
  input  wire [2:0]    io_config_frame_dataLength,
  input  wire [0:0]    io_config_frame_stop,
  input  wire [1:0]    io_config_frame_parity,
  input  wire [19:0]   io_config_clockDivider,
  input  wire          io_write_valid,
  output reg           io_write_ready,
  input  wire [7:0]    io_write_payload,
  output wire          io_read_valid,
  input  wire          io_read_ready,
  output wire [7:0]    io_read_payload,
  output wire          io_uart_txd,
  input  wire          io_uart_rxd,
  output wire          io_readError,
  input  wire          io_writeBreak,
  output wire          io_readBreak,
  input  wire          c3_clk0,
  input  wire          c3_rst0
);
  localparam UartStopType_ONE = 1'd0;
  localparam UartStopType_TWO = 1'd1;
  localparam UartParityType_NONE = 2'd0;
  localparam UartParityType_EVEN = 2'd1;
  localparam UartParityType_ODD = 2'd2;

  wire                tx_io_write_ready;
  wire                tx_io_txd;
  wire                rx_io_read_valid;
  wire       [7:0]    rx_io_read_payload;
  wire                rx_io_rts;
  wire                rx_io_error;
  wire                rx_io_break;
  reg        [19:0]   clockDivider_counter;
  wire                clockDivider_tick;
  reg                 clockDivider_tickReg;
  reg                 io_write_throwWhen_valid;
  wire                io_write_throwWhen_ready;
  wire       [7:0]    io_write_throwWhen_payload;
  `ifndef SYNTHESIS
  reg [23:0] io_config_frame_stop_string;
  reg [31:0] io_config_frame_parity_string;
  `endif


  UartCtrlTx tx (
    .io_configFrame_dataLength (io_config_frame_dataLength[2:0]), //i
    .io_configFrame_stop       (io_config_frame_stop           ), //i
    .io_configFrame_parity     (io_config_frame_parity[1:0]    ), //i
    .io_samplingTick           (clockDivider_tickReg           ), //i
    .io_write_valid            (io_write_throwWhen_valid       ), //i
    .io_write_ready            (tx_io_write_ready              ), //o
    .io_write_payload          (io_write_throwWhen_payload[7:0]), //i
    .io_cts                    (1'b0                           ), //i
    .io_txd                    (tx_io_txd                      ), //o
    .io_break                  (io_writeBreak                  ), //i
    .c3_clk0                   (c3_clk0                        ), //i
    .c3_rst0                   (c3_rst0                        )  //i
  );
  UartCtrlRx rx (
    .io_configFrame_dataLength (io_config_frame_dataLength[2:0]), //i
    .io_configFrame_stop       (io_config_frame_stop           ), //i
    .io_configFrame_parity     (io_config_frame_parity[1:0]    ), //i
    .io_samplingTick           (clockDivider_tickReg           ), //i
    .io_read_valid             (rx_io_read_valid               ), //o
    .io_read_ready             (io_read_ready                  ), //i
    .io_read_payload           (rx_io_read_payload[7:0]        ), //o
    .io_rxd                    (io_uart_rxd                    ), //i
    .io_rts                    (rx_io_rts                      ), //o
    .io_error                  (rx_io_error                    ), //o
    .io_break                  (rx_io_break                    ), //o
    .c3_clk0                   (c3_clk0                        ), //i
    .c3_rst0                   (c3_rst0                        )  //i
  );
  `ifndef SYNTHESIS
  always @(*) begin
    case(io_config_frame_stop)
      UartStopType_ONE : io_config_frame_stop_string = "ONE";
      UartStopType_TWO : io_config_frame_stop_string = "TWO";
      default : io_config_frame_stop_string = "???";
    endcase
  end
  always @(*) begin
    case(io_config_frame_parity)
      UartParityType_NONE : io_config_frame_parity_string = "NONE";
      UartParityType_EVEN : io_config_frame_parity_string = "EVEN";
      UartParityType_ODD : io_config_frame_parity_string = "ODD ";
      default : io_config_frame_parity_string = "????";
    endcase
  end
  `endif

  assign clockDivider_tick = (clockDivider_counter == 20'h0);
  always @(*) begin
    io_write_throwWhen_valid = io_write_valid;
    if(rx_io_break) begin
      io_write_throwWhen_valid = 1'b0;
    end
  end

  always @(*) begin
    io_write_ready = io_write_throwWhen_ready;
    if(rx_io_break) begin
      io_write_ready = 1'b1;
    end
  end

  assign io_write_throwWhen_payload = io_write_payload;
  assign io_write_throwWhen_ready = tx_io_write_ready;
  assign io_read_valid = rx_io_read_valid;
  assign io_read_payload = rx_io_read_payload;
  assign io_uart_txd = tx_io_txd;
  assign io_readError = rx_io_error;
  assign io_readBreak = rx_io_break;
  always @(posedge c3_clk0) begin
    if(c3_rst0) begin
      clockDivider_counter <= 20'h0;
      clockDivider_tickReg <= 1'b0;
    end else begin
      clockDivider_tickReg <= clockDivider_tick;
      clockDivider_counter <= (clockDivider_counter - 20'h00001);
      if(clockDivider_tick) begin
        clockDivider_counter <= io_config_clockDivider;
      end
    end
  end


endmodule

module UartCtrlRx (
  input  wire [2:0]    io_configFrame_dataLength,
  input  wire [0:0]    io_configFrame_stop,
  input  wire [1:0]    io_configFrame_parity,
  input  wire          io_samplingTick,
  output wire          io_read_valid,
  input  wire          io_read_ready,
  output wire [7:0]    io_read_payload,
  input  wire          io_rxd,
  output wire          io_rts,
  output reg           io_error,
  output wire          io_break,
  input  wire          c3_clk0,
  input  wire          c3_rst0
);
  localparam UartStopType_ONE = 1'd0;
  localparam UartStopType_TWO = 1'd1;
  localparam UartParityType_NONE = 2'd0;
  localparam UartParityType_EVEN = 2'd1;
  localparam UartParityType_ODD = 2'd2;
  localparam UartCtrlRxState_IDLE = 3'd0;
  localparam UartCtrlRxState_START = 3'd1;
  localparam UartCtrlRxState_DATA = 3'd2;
  localparam UartCtrlRxState_PARITY = 3'd3;
  localparam UartCtrlRxState_STOP = 3'd4;

  wire                io_rxd_buffercc_io_dataOut;
  wire                _zz_sampler_value;
  wire                _zz_sampler_value_1;
  wire                _zz_sampler_value_2;
  wire                _zz_sampler_value_3;
  wire                _zz_sampler_value_4;
  wire                _zz_sampler_value_5;
  wire                _zz_sampler_value_6;
  wire       [2:0]    _zz_when_UartCtrlRx_l139;
  wire       [0:0]    _zz_when_UartCtrlRx_l139_1;
  reg                 _zz_io_rts;
  wire                sampler_synchroniser;
  wire                sampler_samples_0;
  reg                 sampler_samples_1;
  reg                 sampler_samples_2;
  reg                 sampler_samples_3;
  reg                 sampler_samples_4;
  reg                 sampler_value;
  reg                 sampler_tick;
  reg        [2:0]    bitTimer_counter;
  reg                 bitTimer_tick;
  wire                when_UartCtrlRx_l43;
  reg        [2:0]    bitCounter_value;
  reg        [6:0]    break_counter;
  wire                break_valid;
  wire                when_UartCtrlRx_l69;
  reg        [2:0]    stateMachine_state;
  reg                 stateMachine_parity;
  reg        [7:0]    stateMachine_shifter;
  reg                 stateMachine_validReg;
  wire                when_UartCtrlRx_l93;
  wire                when_UartCtrlRx_l103;
  wire                when_UartCtrlRx_l111;
  wire                when_UartCtrlRx_l113;
  wire                when_UartCtrlRx_l125;
  wire                when_UartCtrlRx_l136;
  wire                when_UartCtrlRx_l139;
  `ifndef SYNTHESIS
  reg [23:0] io_configFrame_stop_string;
  reg [31:0] io_configFrame_parity_string;
  reg [47:0] stateMachine_state_string;
  `endif


  assign _zz_when_UartCtrlRx_l139_1 = ((io_configFrame_stop == UartStopType_ONE) ? 1'b0 : 1'b1);
  assign _zz_when_UartCtrlRx_l139 = {2'd0, _zz_when_UartCtrlRx_l139_1};
  assign _zz_sampler_value = ((((1'b0 || ((_zz_sampler_value_1 && sampler_samples_1) && sampler_samples_2)) || (((_zz_sampler_value_2 && sampler_samples_0) && sampler_samples_1) && sampler_samples_3)) || (((1'b1 && sampler_samples_0) && sampler_samples_2) && sampler_samples_3)) || (((1'b1 && sampler_samples_1) && sampler_samples_2) && sampler_samples_3));
  assign _zz_sampler_value_3 = (((1'b1 && sampler_samples_0) && sampler_samples_1) && sampler_samples_4);
  assign _zz_sampler_value_4 = ((1'b1 && sampler_samples_0) && sampler_samples_2);
  assign _zz_sampler_value_5 = (1'b1 && sampler_samples_1);
  assign _zz_sampler_value_6 = 1'b1;
  assign _zz_sampler_value_1 = (1'b1 && sampler_samples_0);
  assign _zz_sampler_value_2 = 1'b1;
  (* keep_hierarchy = "TRUE" *) BufferCC io_rxd_buffercc (
    .io_dataIn  (io_rxd                    ), //i
    .io_dataOut (io_rxd_buffercc_io_dataOut), //o
    .c3_clk0    (c3_clk0                   ), //i
    .c3_rst0    (c3_rst0                   )  //i
  );
  `ifndef SYNTHESIS
  always @(*) begin
    case(io_configFrame_stop)
      UartStopType_ONE : io_configFrame_stop_string = "ONE";
      UartStopType_TWO : io_configFrame_stop_string = "TWO";
      default : io_configFrame_stop_string = "???";
    endcase
  end
  always @(*) begin
    case(io_configFrame_parity)
      UartParityType_NONE : io_configFrame_parity_string = "NONE";
      UartParityType_EVEN : io_configFrame_parity_string = "EVEN";
      UartParityType_ODD : io_configFrame_parity_string = "ODD ";
      default : io_configFrame_parity_string = "????";
    endcase
  end
  always @(*) begin
    case(stateMachine_state)
      UartCtrlRxState_IDLE : stateMachine_state_string = "IDLE  ";
      UartCtrlRxState_START : stateMachine_state_string = "START ";
      UartCtrlRxState_DATA : stateMachine_state_string = "DATA  ";
      UartCtrlRxState_PARITY : stateMachine_state_string = "PARITY";
      UartCtrlRxState_STOP : stateMachine_state_string = "STOP  ";
      default : stateMachine_state_string = "??????";
    endcase
  end
  `endif

  always @(*) begin
    io_error = 1'b0;
    case(stateMachine_state)
      UartCtrlRxState_IDLE : begin
      end
      UartCtrlRxState_START : begin
      end
      UartCtrlRxState_DATA : begin
      end
      UartCtrlRxState_PARITY : begin
        if(bitTimer_tick) begin
          if(!when_UartCtrlRx_l125) begin
            io_error = 1'b1;
          end
        end
      end
      default : begin
        if(bitTimer_tick) begin
          if(when_UartCtrlRx_l136) begin
            io_error = 1'b1;
          end
        end
      end
    endcase
  end

  assign io_rts = _zz_io_rts;
  assign sampler_synchroniser = io_rxd_buffercc_io_dataOut;
  assign sampler_samples_0 = sampler_synchroniser;
  always @(*) begin
    bitTimer_tick = 1'b0;
    if(sampler_tick) begin
      if(when_UartCtrlRx_l43) begin
        bitTimer_tick = 1'b1;
      end
    end
  end

  assign when_UartCtrlRx_l43 = (bitTimer_counter == 3'b000);
  assign break_valid = (break_counter == 7'h68);
  assign when_UartCtrlRx_l69 = (io_samplingTick && (! break_valid));
  assign io_break = break_valid;
  assign io_read_valid = stateMachine_validReg;
  assign when_UartCtrlRx_l93 = ((sampler_tick && (! sampler_value)) && (! break_valid));
  assign when_UartCtrlRx_l103 = (sampler_value == 1'b1);
  assign when_UartCtrlRx_l111 = (bitCounter_value == io_configFrame_dataLength);
  assign when_UartCtrlRx_l113 = (io_configFrame_parity == UartParityType_NONE);
  assign when_UartCtrlRx_l125 = (stateMachine_parity == sampler_value);
  assign when_UartCtrlRx_l136 = (! sampler_value);
  assign when_UartCtrlRx_l139 = (bitCounter_value == _zz_when_UartCtrlRx_l139);
  assign io_read_payload = stateMachine_shifter;
  always @(posedge c3_clk0) begin
    if(c3_rst0) begin
      _zz_io_rts <= 1'b0;
      sampler_samples_1 <= 1'b1;
      sampler_samples_2 <= 1'b1;
      sampler_samples_3 <= 1'b1;
      sampler_samples_4 <= 1'b1;
      sampler_value <= 1'b1;
      sampler_tick <= 1'b0;
      break_counter <= 7'h0;
      stateMachine_state <= UartCtrlRxState_IDLE;
      stateMachine_validReg <= 1'b0;
    end else begin
      _zz_io_rts <= (! io_read_ready);
      if(io_samplingTick) begin
        sampler_samples_1 <= sampler_samples_0;
      end
      if(io_samplingTick) begin
        sampler_samples_2 <= sampler_samples_1;
      end
      if(io_samplingTick) begin
        sampler_samples_3 <= sampler_samples_2;
      end
      if(io_samplingTick) begin
        sampler_samples_4 <= sampler_samples_3;
      end
      sampler_value <= ((((((_zz_sampler_value || _zz_sampler_value_3) || (_zz_sampler_value_4 && sampler_samples_4)) || ((_zz_sampler_value_5 && sampler_samples_2) && sampler_samples_4)) || (((_zz_sampler_value_6 && sampler_samples_0) && sampler_samples_3) && sampler_samples_4)) || (((1'b1 && sampler_samples_1) && sampler_samples_3) && sampler_samples_4)) || (((1'b1 && sampler_samples_2) && sampler_samples_3) && sampler_samples_4));
      sampler_tick <= io_samplingTick;
      if(sampler_value) begin
        break_counter <= 7'h0;
      end else begin
        if(when_UartCtrlRx_l69) begin
          break_counter <= (break_counter + 7'h01);
        end
      end
      stateMachine_validReg <= 1'b0;
      case(stateMachine_state)
        UartCtrlRxState_IDLE : begin
          if(when_UartCtrlRx_l93) begin
            stateMachine_state <= UartCtrlRxState_START;
          end
        end
        UartCtrlRxState_START : begin
          if(bitTimer_tick) begin
            stateMachine_state <= UartCtrlRxState_DATA;
            if(when_UartCtrlRx_l103) begin
              stateMachine_state <= UartCtrlRxState_IDLE;
            end
          end
        end
        UartCtrlRxState_DATA : begin
          if(bitTimer_tick) begin
            if(when_UartCtrlRx_l111) begin
              if(when_UartCtrlRx_l113) begin
                stateMachine_state <= UartCtrlRxState_STOP;
                stateMachine_validReg <= 1'b1;
              end else begin
                stateMachine_state <= UartCtrlRxState_PARITY;
              end
            end
          end
        end
        UartCtrlRxState_PARITY : begin
          if(bitTimer_tick) begin
            if(when_UartCtrlRx_l125) begin
              stateMachine_state <= UartCtrlRxState_STOP;
              stateMachine_validReg <= 1'b1;
            end else begin
              stateMachine_state <= UartCtrlRxState_IDLE;
            end
          end
        end
        default : begin
          if(bitTimer_tick) begin
            if(when_UartCtrlRx_l136) begin
              stateMachine_state <= UartCtrlRxState_IDLE;
            end else begin
              if(when_UartCtrlRx_l139) begin
                stateMachine_state <= UartCtrlRxState_IDLE;
              end
            end
          end
        end
      endcase
    end
  end

  always @(posedge c3_clk0) begin
    if(sampler_tick) begin
      bitTimer_counter <= (bitTimer_counter - 3'b001);
    end
    if(bitTimer_tick) begin
      bitCounter_value <= (bitCounter_value + 3'b001);
    end
    if(bitTimer_tick) begin
      stateMachine_parity <= (stateMachine_parity ^ sampler_value);
    end
    case(stateMachine_state)
      UartCtrlRxState_IDLE : begin
        if(when_UartCtrlRx_l93) begin
          bitTimer_counter <= 3'b010;
        end
      end
      UartCtrlRxState_START : begin
        if(bitTimer_tick) begin
          bitCounter_value <= 3'b000;
          stateMachine_parity <= (io_configFrame_parity == UartParityType_ODD);
        end
      end
      UartCtrlRxState_DATA : begin
        if(bitTimer_tick) begin
          stateMachine_shifter[bitCounter_value] <= sampler_value;
          if(when_UartCtrlRx_l111) begin
            bitCounter_value <= 3'b000;
          end
        end
      end
      UartCtrlRxState_PARITY : begin
        if(bitTimer_tick) begin
          bitCounter_value <= 3'b000;
        end
      end
      default : begin
      end
    endcase
  end


endmodule

module UartCtrlTx (
  input  wire [2:0]    io_configFrame_dataLength,
  input  wire [0:0]    io_configFrame_stop,
  input  wire [1:0]    io_configFrame_parity,
  input  wire          io_samplingTick,
  input  wire          io_write_valid,
  output reg           io_write_ready,
  input  wire [7:0]    io_write_payload,
  input  wire          io_cts,
  output wire          io_txd,
  input  wire          io_break,
  input  wire          c3_clk0,
  input  wire          c3_rst0
);
  localparam UartStopType_ONE = 1'd0;
  localparam UartStopType_TWO = 1'd1;
  localparam UartParityType_NONE = 2'd0;
  localparam UartParityType_EVEN = 2'd1;
  localparam UartParityType_ODD = 2'd2;
  localparam UartCtrlTxState_IDLE = 3'd0;
  localparam UartCtrlTxState_START = 3'd1;
  localparam UartCtrlTxState_DATA = 3'd2;
  localparam UartCtrlTxState_PARITY = 3'd3;
  localparam UartCtrlTxState_STOP = 3'd4;

  wire       [2:0]    _zz_clockDivider_counter_valueNext;
  wire       [0:0]    _zz_clockDivider_counter_valueNext_1;
  wire       [2:0]    _zz_when_UartCtrlTx_l93;
  wire       [0:0]    _zz_when_UartCtrlTx_l93_1;
  reg                 clockDivider_counter_willIncrement;
  wire                clockDivider_counter_willClear;
  reg        [2:0]    clockDivider_counter_valueNext;
  reg        [2:0]    clockDivider_counter_value;
  wire                clockDivider_counter_willOverflowIfInc;
  wire                clockDivider_counter_willOverflow;
  reg        [2:0]    tickCounter_value;
  reg        [2:0]    stateMachine_state;
  reg                 stateMachine_parity;
  reg                 stateMachine_txd;
  wire                when_UartCtrlTx_l58;
  wire                when_UartCtrlTx_l73;
  wire                when_UartCtrlTx_l76;
  wire                when_UartCtrlTx_l93;
  wire       [2:0]    _zz_stateMachine_state;
  reg                 _zz_io_txd;
  `ifndef SYNTHESIS
  reg [23:0] io_configFrame_stop_string;
  reg [31:0] io_configFrame_parity_string;
  reg [47:0] stateMachine_state_string;
  reg [47:0] _zz_stateMachine_state_string;
  `endif


  assign _zz_clockDivider_counter_valueNext_1 = clockDivider_counter_willIncrement;
  assign _zz_clockDivider_counter_valueNext = {2'd0, _zz_clockDivider_counter_valueNext_1};
  assign _zz_when_UartCtrlTx_l93_1 = ((io_configFrame_stop == UartStopType_ONE) ? 1'b0 : 1'b1);
  assign _zz_when_UartCtrlTx_l93 = {2'd0, _zz_when_UartCtrlTx_l93_1};
  `ifndef SYNTHESIS
  always @(*) begin
    case(io_configFrame_stop)
      UartStopType_ONE : io_configFrame_stop_string = "ONE";
      UartStopType_TWO : io_configFrame_stop_string = "TWO";
      default : io_configFrame_stop_string = "???";
    endcase
  end
  always @(*) begin
    case(io_configFrame_parity)
      UartParityType_NONE : io_configFrame_parity_string = "NONE";
      UartParityType_EVEN : io_configFrame_parity_string = "EVEN";
      UartParityType_ODD : io_configFrame_parity_string = "ODD ";
      default : io_configFrame_parity_string = "????";
    endcase
  end
  always @(*) begin
    case(stateMachine_state)
      UartCtrlTxState_IDLE : stateMachine_state_string = "IDLE  ";
      UartCtrlTxState_START : stateMachine_state_string = "START ";
      UartCtrlTxState_DATA : stateMachine_state_string = "DATA  ";
      UartCtrlTxState_PARITY : stateMachine_state_string = "PARITY";
      UartCtrlTxState_STOP : stateMachine_state_string = "STOP  ";
      default : stateMachine_state_string = "??????";
    endcase
  end
  always @(*) begin
    case(_zz_stateMachine_state)
      UartCtrlTxState_IDLE : _zz_stateMachine_state_string = "IDLE  ";
      UartCtrlTxState_START : _zz_stateMachine_state_string = "START ";
      UartCtrlTxState_DATA : _zz_stateMachine_state_string = "DATA  ";
      UartCtrlTxState_PARITY : _zz_stateMachine_state_string = "PARITY";
      UartCtrlTxState_STOP : _zz_stateMachine_state_string = "STOP  ";
      default : _zz_stateMachine_state_string = "??????";
    endcase
  end
  `endif

  always @(*) begin
    clockDivider_counter_willIncrement = 1'b0;
    if(io_samplingTick) begin
      clockDivider_counter_willIncrement = 1'b1;
    end
  end

  assign clockDivider_counter_willClear = 1'b0;
  assign clockDivider_counter_willOverflowIfInc = (clockDivider_counter_value == 3'b111);
  assign clockDivider_counter_willOverflow = (clockDivider_counter_willOverflowIfInc && clockDivider_counter_willIncrement);
  always @(*) begin
    clockDivider_counter_valueNext = (clockDivider_counter_value + _zz_clockDivider_counter_valueNext);
    if(clockDivider_counter_willClear) begin
      clockDivider_counter_valueNext = 3'b000;
    end
  end

  always @(*) begin
    stateMachine_txd = 1'b1;
    case(stateMachine_state)
      UartCtrlTxState_IDLE : begin
      end
      UartCtrlTxState_START : begin
        stateMachine_txd = 1'b0;
      end
      UartCtrlTxState_DATA : begin
        stateMachine_txd = io_write_payload[tickCounter_value];
      end
      UartCtrlTxState_PARITY : begin
        stateMachine_txd = stateMachine_parity;
      end
      default : begin
      end
    endcase
  end

  always @(*) begin
    io_write_ready = io_break;
    case(stateMachine_state)
      UartCtrlTxState_IDLE : begin
      end
      UartCtrlTxState_START : begin
      end
      UartCtrlTxState_DATA : begin
        if(clockDivider_counter_willOverflow) begin
          if(when_UartCtrlTx_l73) begin
            io_write_ready = 1'b1;
          end
        end
      end
      UartCtrlTxState_PARITY : begin
      end
      default : begin
      end
    endcase
  end

  assign when_UartCtrlTx_l58 = ((io_write_valid && (! io_cts)) && clockDivider_counter_willOverflow);
  assign when_UartCtrlTx_l73 = (tickCounter_value == io_configFrame_dataLength);
  assign when_UartCtrlTx_l76 = (io_configFrame_parity == UartParityType_NONE);
  assign when_UartCtrlTx_l93 = (tickCounter_value == _zz_when_UartCtrlTx_l93);
  assign _zz_stateMachine_state = (io_write_valid ? UartCtrlTxState_START : UartCtrlTxState_IDLE);
  assign io_txd = _zz_io_txd;
  always @(posedge c3_clk0) begin
    if(c3_rst0) begin
      clockDivider_counter_value <= 3'b000;
      stateMachine_state <= UartCtrlTxState_IDLE;
      _zz_io_txd <= 1'b1;
    end else begin
      clockDivider_counter_value <= clockDivider_counter_valueNext;
      case(stateMachine_state)
        UartCtrlTxState_IDLE : begin
          if(when_UartCtrlTx_l58) begin
            stateMachine_state <= UartCtrlTxState_START;
          end
        end
        UartCtrlTxState_START : begin
          if(clockDivider_counter_willOverflow) begin
            stateMachine_state <= UartCtrlTxState_DATA;
          end
        end
        UartCtrlTxState_DATA : begin
          if(clockDivider_counter_willOverflow) begin
            if(when_UartCtrlTx_l73) begin
              if(when_UartCtrlTx_l76) begin
                stateMachine_state <= UartCtrlTxState_STOP;
              end else begin
                stateMachine_state <= UartCtrlTxState_PARITY;
              end
            end
          end
        end
        UartCtrlTxState_PARITY : begin
          if(clockDivider_counter_willOverflow) begin
            stateMachine_state <= UartCtrlTxState_STOP;
          end
        end
        default : begin
          if(clockDivider_counter_willOverflow) begin
            if(when_UartCtrlTx_l93) begin
              stateMachine_state <= _zz_stateMachine_state;
            end
          end
        end
      endcase
      _zz_io_txd <= (stateMachine_txd && (! io_break));
    end
  end

  always @(posedge c3_clk0) begin
    if(clockDivider_counter_willOverflow) begin
      tickCounter_value <= (tickCounter_value + 3'b001);
    end
    if(clockDivider_counter_willOverflow) begin
      stateMachine_parity <= (stateMachine_parity ^ stateMachine_txd);
    end
    case(stateMachine_state)
      UartCtrlTxState_IDLE : begin
      end
      UartCtrlTxState_START : begin
        if(clockDivider_counter_willOverflow) begin
          stateMachine_parity <= (io_configFrame_parity == UartParityType_ODD);
          tickCounter_value <= 3'b000;
        end
      end
      UartCtrlTxState_DATA : begin
        if(clockDivider_counter_willOverflow) begin
          if(when_UartCtrlTx_l73) begin
            tickCounter_value <= 3'b000;
          end
        end
      end
      UartCtrlTxState_PARITY : begin
        if(clockDivider_counter_willOverflow) begin
          tickCounter_value <= 3'b000;
        end
      end
      default : begin
      end
    endcase
  end


endmodule

module BufferCC (
  input  wire          io_dataIn,
  output wire          io_dataOut,
  input  wire          c3_clk0,
  input  wire          c3_rst0
);

  (* async_reg = "true" , altera_attribute = "-name ADV_NETLIST_OPT_ALLOWED NEVER_ALLOW" *) reg                 buffers_0;
  (* async_reg = "true" *) reg                 buffers_1;

  assign io_dataOut = buffers_1;
  always @(posedge c3_clk0) begin
    if(c3_rst0) begin
      buffers_0 <= 1'b0;
      buffers_1 <= 1'b0;
    end else begin
      buffers_0 <= io_dataIn;
      buffers_1 <= buffers_0;
    end
  end


endmodule
