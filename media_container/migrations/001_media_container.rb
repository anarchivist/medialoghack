require 'sequel'
require 'sequel/adapters/shared/mysql'

Sequel.extension :migration

module Sequel

  module Schema

    class AlterTableGenerator

    def TextField(field, opts = {})
      if $db_type == :derby
        add_column field, :clob, opts
      else
        add_column field, :text, opts
      end
    end


    def DynamicEnum(field, opts = {})
      add_column field, :integer, opts
      add_foreign_key([field], :enumeration_value, :key => :id)
    end

    end

    end

end

Sequel.migration do
  up do

    create_editable_enum('container_media_format', ["3_5_inch_floppy", "5_25_inch_floppy", "8_inch_floppy", "cd", "cd-r", "cd-rw", "dvd", "flash", "hard_disk", "jaz_cartridge", "other", "zip_disk"])

    create_editable_enum('container_media_density', ["single", "double", "quad", "high"])

    alter_table(:container) do
      DynamicEnum :media_format_id
      DynamicEnum :media_density_id
      TextField :media_label_transcription
      String :media_manufacturer
      String :media_serial_number
    end

  end

end
